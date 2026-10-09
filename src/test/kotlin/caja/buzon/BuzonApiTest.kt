package caja.buzon

import caja.CajaApiTest
import caja.comun.Candado
import caja.comun.Candados
import caja.comun.Transaccion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// el publicador del buzón contra un sistema de origen falso por http (CobrarConElOrigenApagadoTest,
// CadaEventoEnSuTransaccionTest y UnPagoNoMuereSinCredencialTest de caja), los pagos sin entregar, su explicación y la
// defensa frente a un pago_evento inventado por fuera de caja. la API genérica ya no escribe ningún objeto de caja
// (GuardiaDeEscrituras, caja-backend#20): lo forjado se escribe en la base (forjarEnLaBase, cambiarEnLaBase), la
// puerta que queda abierta. el buzón está encendido, pero su bucle espera una hora:
// cada prueba da sus vueltas a mano, y el contexto se cierra con la clase (DirtiesContext), así que su bucle no queda
// vivo para las demás. la base es compartida: una vuelta lee TODO lo pendiente (por-vuelta alto) y los eventos de otras
// pruebas, sin destino configurado, solo suman intentos
@ExtendWith(OutputCaptureExtension::class)
@DirtiesContext
@TestPropertySource(
    properties = [
        "caja.buzon.habilitado=true",
        "caja.buzon.intervalo=PT1H",
        "caja.buzon.intentos=3",
        "caja.buzon.por-vuelta=100000",
        "caja.buzon.timeout=5s",
        "caja.conciliacion.responsable=Ana Quispe",
        "caja.conciliacion.canal=conciliacion@muni.gob.pe"
    ]
)
class BuzonApiTest : CajaApiTest() {
    @Autowired
    lateinit var publicador: PublicadorDelBuzon

    @Autowired
    lateinit var store: BuzonStore

    @Autowired
    lateinit var bucle: BucleDelBuzon

    @Autowired
    lateinit var db: DatabaseClient

    @Autowired
    lateinit var schemas: WasichaiSchemas

    @Autowired
    lateinit var candados: Candados

    @Autowired
    lateinit var transaccion: Transaccion

    // del cobro con el origen caído

    @Test
    fun `se cobra con el origen caido y el pago queda EN_TRANSITO con su hora, y reintenta sin perderlo`() {
        val antes = Instant.now()
        val cobro = cobrar(CAIDO)
        assertEquals("EN_TRANSITO", cobro.respuesta["estado_del_pago"].asString())
        val nacido = evento(cobro.pagoId)
        assertEquals("PENDIENTE", nacido["attributes"]["estado"].asString())
        assertEquals(0, nacido["attributes"]["intentos"].asInt())
        assertTrue(nacido["attributes"]["entregado_en"].isNull)
        val hora = Instant.parse(nacido["createdAt"].asString())
        assertTrue(!hora.isBefore(antes.minusSeconds(1)) && !hora.isAfter(Instant.now()), "la hora del tránsito: $hora")

        vuelta()

        val tras = evento(cobro.pagoId)["attributes"]
        assertEquals("PENDIENTE", tras["estado"].asString(), "no se pierde: sigue en la cola")
        assertEquals(1, tras["intentos"].asInt())
        assertTrue(tras["ultimo_error"].asString().contains(CAIDO), tras.toString())
    }

    @Test
    fun `el cuerpo lleva la referencia y no la imputacion, y al entregarse queda ENTREGADO con su hora`() {
        val cobro = cobrar(PRUEBAS, importe = "75.50")
        origen.contestar(cobro.pagoId, 201)

        vuelta()

        val recibida = origen.de(cobro.pagoId).single()
        assertEquals("/pagos", recibida.ruta)
        assertTrue(recibida.tipo!!.startsWith("application/json"), recibida.tipo)
        assertNull(recibida.autorizacion, "sin token configurado no se manda ninguno")
        val cuerpo = json.readTree(recibida.cuerpo)
        assertEquals(cobro.referencia, cuerpo["ordenes"][0]["referenciaExterna"].asString())
        assertEquals("75.50", cuerpo["ordenes"][0]["importe"].asString(), "el importe viaja como cadena")
        assertFalse(recibida.cuerpo.contains("insoluto") || recibida.cuerpo.contains("interes"), recibida.cuerpo)
        // el cuerpo congelado, tal cual se escribió al cobrar
        assertEquals(evento(cobro.pagoId)["attributes"]["cuerpo"].asString(), recibida.cuerpo)
        val entregado = evento(cobro.pagoId)["attributes"]
        assertEquals("ENTREGADO", entregado["estado"].asString())
        assertEquals(1, entregado["intentos"].asInt())
        assertFalse(entregado["entregado_en"].isNull)

        // entregado, no se vuelve a enviar
        vuelta()
        assertEquals(1, origen.de(cobro.pagoId).size)
    }

    @Test
    fun `agotados los intentos muere y avisa al responsable por su canal`(salida: CapturedOutput) {
        val cobro = cobrar(CAIDO)

        repeat(3) { vuelta() }

        val muerto = evento(cobro.pagoId)["attributes"]
        assertEquals("MUERTO", muerto["estado"].asString())
        assertEquals(3, muerto["intentos"].asInt())
        val alerta = alertaDe(salida, cobro.pagoId)
        assertTrue(alerta.contains(" ERROR "), alerta)
        assertTrue(alerta.contains(": DINERO COBRADO SIN REGISTRAR"), alerta)
        assertTrue(alerta.contains("Ana Quispe") && alerta.contains("conciliacion@muni.gob.pe"), alerta)
        assertTrue(alerta.contains(cobro.numero), alerta)

        // muerto, no se vuelve a intentar
        vuelta()
        assertEquals(3, evento(cobro.pagoId)["attributes"]["intentos"].asInt())
    }

    // de lo que contesta el destino

    @Test
    fun `un 401 sin credencial sigue vivo y la segunda vuelta vuelve a llamar, un 422 muere ya`() {
        val sinCredencial = cobrar(PRUEBAS)
        val rechazado = cobrar(PRUEBAS)
        origen.contestar(sinCredencial.pagoId, 401)
        origen.contestar(rechazado.pagoId, 422, """{"detail":"la referencia no es de este sistema"}""")

        vuelta()

        val vivo = evento(sinCredencial.pagoId)["attributes"]
        assertEquals("PENDIENTE", vivo["estado"].asString())
        assertEquals(1, vivo["intentos"].asInt())
        assertTrue(vivo["ultimo_error"].asString().contains("caja.buzon.destinos.$PRUEBAS.token"), vivo.toString())
        val muerto = evento(rechazado.pagoId)["attributes"]
        assertEquals("MUERTO", muerto["estado"].asString())
        assertEquals(1, muerto["intentos"].asInt(), "un rechazo no gasta los demás intentos")
        assertTrue(muerto["ultimo_error"].asString().contains("la referencia no es de este sistema"), muerto.toString())

        vuelta()

        assertEquals(2, origen.de(sinCredencial.pagoId).size, "el 401 se reintenta de verdad")
        assertEquals(2, evento(sinCredencial.pagoId)["attributes"]["intentos"].asInt())
        assertEquals(1, origen.de(rechazado.pagoId).size, "el rechazo no")
    }

    @Test
    fun `el token va en la cabecera y nunca en el cuerpo ni en ultimo_error`() {
        val cobro = cobrar(CON_TOKEN)
        origen.contestar(cobro.pagoId, 403, """{"error":"forbidden","peticion":{"Authorization":"Bearer $TOKEN"}}""")

        vuelta()

        val recibida = origen.de(cobro.pagoId).single()
        assertEquals("/con-token/pagos", recibida.ruta)
        assertEquals("Bearer $TOKEN", recibida.autorizacion)
        assertFalse(recibida.cuerpo.contains(TOKEN), recibida.cuerpo)
        val tras = evento(cobro.pagoId)["attributes"]
        assertEquals("PENDIENTE", tras["estado"].asString(), "un 403 se reintenta")
        assertTrue(tras["ultimo_error"].asString().contains("forbidden"), tras.toString())
        assertFalse(tras["ultimo_error"].asString().contains(TOKEN), tras.toString())
    }

    @Test
    fun `sin url configurada no contesta y lo dice`() {
        val cobro = cobrar(SIN_URL)
        vuelta()
        val tras = evento(cobro.pagoId)["attributes"]
        assertEquals("PENDIENTE", tras["estado"].asString())
        assertTrue(tras["ultimo_error"].asString().contains("caja.buzon.destinos.$SIN_URL.url"), tras.toString())
    }

    // de las marcas

    @Test
    fun `dos publicadores que leyeron el mismo evento y fallan los dos cuentan un solo intento`() =
        runBlocking {
            val cobro = cobrar(PRUEBAS)
            val (buzon, leido) = leidoDe(cobro)

            publicador.entregarUno(buzon, leido)
            publicador.entregarUno(buzon, leido)

            assertEquals(2, origen.de(cobro.pagoId).size, "los dos llamaron")
            assertEquals(1, evento(cobro.pagoId)["attributes"]["intentos"].asInt(), "y se contó uno")
        }

    @Test
    fun `una marca espera el candado de su evento, el de la explicacion, y a la vez cuentan un solo intento`() =
        runBlocking {
            val cobro = cobrar(PRUEBAS)
            val (buzon, leido) = leidoDe(cobro)
            val tomado = CompletableDeferred<Unit>()
            val soltar = CompletableDeferred<Unit>()
            // otra transacción con el candado del evento, como lo toma ExplicarPagoSinEntregar
            val otra =
                async(Dispatchers.IO) {
                    transaccion.en {
                        candados.bloquear(Candado.PAGO, cobro.pagoId)
                        tomado.complete(Unit)
                        soltar.await()
                    }
                }
            tomado.await()
            val marca = async(Dispatchers.IO) { store.fallido(buzon, leido, "no contesta", muere = false) }
            delay(500)
            assertFalse(marca.isCompleted, "la marca espera el candado del evento")
            // mientras espera, otro publicador ya anotó su intento
            cambiarEnLaBase("pago_evento", leido.id.toString(), "intentos" to 1, "ultimo_error" to "lo anotó otro")
            soltar.complete(Unit)
            otra.await()
            assertFalse(marca.await(), "bajo el candado lo releyó: ya no estaba como se leyó, y no anota")
            assertEquals("lo anotó otro", evento(cobro.pagoId)["attributes"]["ultimo_error"].asString())

            // cuatro marcas a la vez sobre lo mismo leído: el candado las pone en fila, y solo la primera lo encuentra igual
            val (_, releido) = leidoDe(cobro)
            val anotadas = (1..4).map { async(Dispatchers.IO) { store.fallido(buzon, releido, "no contesta", muere = false) } }.map { it.await() }
            assertEquals(1, anotadas.count { it }, anotadas.toString())
            assertEquals(2, evento(cobro.pagoId)["attributes"]["intentos"].asInt())
        }

    @Test
    fun `la llamada al destino ocurre fuera de toda transaccion, y cada marca se confirma antes de la siguiente llamada`() {
        val primero = cobrar(PRUEBAS)
        val segundo = cobrar(PRUEBAS)
        origen.contestar(primero.pagoId, 200)
        origen.contestar(segundo.pagoId, 200)
        val tabla = runBlocking { tablaDe("pago_evento") }
        var visto: String? = null
        var enTransaccion = -1L
        var bloqueos = -1L
        origen.alRecibir = { pagoId ->
            if (pagoId == segundo.pagoId) {
                // otra conexión, mientras el segundo está en la red
                visto = evento(primero.pagoId)["attributes"]["estado"].asString()
                runBlocking {
                    enTransaccion = contar("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state LIKE 'idle in transaction%'")
                    bloqueos = contar("SELECT count(*) FROM pg_locks WHERE relation = to_regclass('$tabla') AND pid <> pg_backend_pid()")
                }
            }
        }
        try {
            vuelta()
        } finally {
            origen.alRecibir = {}
        }

        assertEquals("ENTREGADO", visto, "el primero ya estaba confirmado: su marca fue en su propia transacción, antes")
        assertEquals(0L, enTransaccion, "ninguna transacción abierta esperando la respuesta")
        assertEquals(0L, bloqueos, "nadie retiene un candado sobre pago_evento durante la llamada")
        assertEquals("ENTREGADO", evento(segundo.pagoId)["attributes"]["estado"].asString())
    }

    @Test
    fun `cada marca deja su auditoria como la plataforma, sin usuario y con su razon fija`() {
        val entregado = cobrar(PRUEBAS)
        val fallido = cobrar(PRUEBAS)
        val muerto = cobrar(PRUEBAS)
        origen.contestar(entregado.pagoId, 200)
        origen.contestar(muerto.pagoId, 422, """{"detail":"rentas no conoce esa orden"}""")
        // al fallido nadie le dijo nada al origen: contesta 503, y sigue PENDIENTE con un intento

        vuelta()

        listOf(
            Triple(entregado, "ENTREGADO", "entrega del buzón: ENTREGADO"),
            Triple(fallido, "PENDIENTE", "entrega del buzón: intento fallido"),
            Triple(muerto, "MUERTO", "entrega del buzón: MUERTO")
        ).forEach { (cobro, estado, razon) ->
            val guardado = evento(cobro.pagoId)
            assertEquals(estado, guardado["attributes"]["estado"].asString())
            val id = guardado["id"].asString()
            val historia = tree(send("GET", "/api/objects/pago_evento/records/$id/history", null, HttpStatus.OK)).toList()
            val marca = historia.single { it["operation"].asString() == "UPDATE" }
            // la razón fija, nunca lo que contestó el destino (eso va en ultimo_error)
            assertEquals(razon, marca["reason"].asString(), marca.toString())
            assertTrue(marca["userEmail"].isNull, "la marca la escribió la plataforma, no una persona: $marca")
            assertFalse(marca.has("serviceAccount"), "ni una cuenta de servicio: $marca")
            val intentos = marca["changes"].toList().single { it["field"].asString() == "intentos" }
            assertEquals(0, intentos["before"].asInt(), marca.toString())
            assertEquals(1, intentos["after"].asInt(), marca.toString())
            val sinUsuario =
                runBlocking {
                    contar(
                        "SELECT count(*) FROM ${schemas.metadata}.audit_log WHERE record_id = '$id'::uuid AND operation = 'UPDATE' " +
                            "AND user_id IS NULL AND reason = '$razon'"
                    )
                }
            assertEquals(1L, sinUsuario, "su fila de auditoría no tiene usuario")
        }
    }

    // de lo que se escribe por fuera de caja

    @Test
    fun `un pago_evento escrito en la base no se envia y muere, y la API generica ni siquiera lo escribe`(salida: CapturedOutput) {
        val cobro = cobrar(PRUEBAS)
        origen.contestar(cobro.pagoId, 200)
        val legitimo = evento(cobro.pagoId)["attributes"]
        val inventadoId = UUID.randomUUID().toString()
        val cuerpo =
            legitimo["cuerpo"]
                .asString()
                .replace(cobro.pagoId, inventadoId)
                .replace("\"total\":\"150.50\"", "\"total\":\"999.99\"")
        assertTrue(cuerpo.contains("999.99"), cuerpo)
        // un CAJERO tiene CREATE sobre pago_evento para cobrar, y la API genérica no lo deja usar fuera de la cobranza
        val atributos =
            mapOf(
                "evento_id" to inventadoId,
                "tipo" to "PAGO_REGISTRADO",
                "sistema_destino" to PRUEBAS,
                "recibo" to legitimo["recibo"].asString(),
                "turno" to legitimo["turno"].asString(),
                "cuerpo" to cuerpo,
                "estado" to "PENDIENTE",
                "intentos" to 0
            )
        send("POST", "/api/objects/pago_evento/records", mapOf("attributes" to atributos), HttpStatus.FORBIDDEN, cuenta("CAJERO").token)
        assertTrue(registros("pago_evento", "evento_id" to inventadoId).isEmpty(), "la API genérica no lo escribió")
        // quien escribe en la base sí puede
        forjarEnLaBase("pago_evento", atributos)

        vuelta()

        assertTrue(origen.de(inventadoId).isEmpty(), "no se envió")
        val muerto = evento(inventadoId)["attributes"]
        assertEquals("MUERTO", muerto["estado"].asString())
        assertTrue(muerto["ultimo_error"].asString().startsWith("el evento no coincide con su recibo"), muerto.toString())
        assertTrue(alertaDe(salida, inventadoId).contains("DINERO COBRADO SIN REGISTRAR"))
        // el legítimo, en la misma vuelta, sí
        assertEquals("ENTREGADO", evento(cobro.pagoId)["attributes"]["estado"].asString())
    }

    @Test
    fun `una copia de un evento legitimo con otro pagoId no se envia, ni con otra referencia`(salida: CapturedOutput) {
        val cobro = cobrar(PRUEBAS)
        origen.contestar(cobro.pagoId, 200)
        val legitimo = evento(cobro.pagoId)["attributes"]
        val exacta = UUID.randomUUID().toString()
        forjar(legitimo, exacta, legitimo["cuerpo"].asString().replace(cobro.pagoId, exacta))
        val otraReferencia = UUID.randomUUID().toString()
        forjar(
            legitimo,
            otraReferencia,
            legitimo["cuerpo"].asString().replace(cobro.pagoId, otraReferencia).replace(cobro.referencia, "OTRA-DEUDA-${unico()}")
        )
        listOf(exacta, otraReferencia).forEach { origen.contestar(it, 200) }

        vuelta()

        assertEquals("ENTREGADO", evento(cobro.pagoId)["attributes"]["estado"].asString())
        listOf(exacta, otraReferencia).forEach { copia ->
            assertTrue(origen.de(copia).isEmpty(), "la copia $copia no se envió")
            val muerta = evento(copia)["attributes"]
            assertEquals("MUERTO", muerta["estado"].asString())
            assertTrue(muerta["ultimo_error"].asString().startsWith("el evento no coincide con su recibo"), muerta.toString())
            assertTrue(muerta["ultimo_error"].asString().contains("copia"), muerta.toString())
            alertaDe(salida, copia)
        }
    }

    @Test
    fun `un cuerpo editado en la base no se envia, con el importe repartido de otro modo o con otra referencia`() {
        val repartido = cobrar(PRUEBAS, importe = "100.00", otros = listOf("50.50"))
        val referida = cobrar(PRUEBAS, importe = "100.00", otros = listOf("50.50"))
        val intacto = cobrar(PRUEBAS, importe = "100.00", otros = listOf("50.50"))
        listOf(repartido, referida, intacto).forEach { origen.contestar(it.pagoId, 200) }
        // el mismo total, 150.50, repartido de otro modo entre las dos órdenes
        val cuerpo = evento(repartido.pagoId)["attributes"]["cuerpo"].asString()
        val otroReparto = cuerpo.replace("\"100.00\"", "\"X\"").replace("\"50.50\"", "\"100.00\"").replace("\"X\"", "\"50.50\"")
        assertTrue(otroReparto != cuerpo)
        cambiarEnLaBase("pago_evento", evento(repartido.pagoId)["id"].asString(), "cuerpo" to otroReparto)
        val referido = evento(referida.pagoId)["attributes"]["cuerpo"].asString().replace(referida.referencia, "OTRA-DEUDA-${unico()}")
        cambiarEnLaBase("pago_evento", evento(referida.pagoId)["id"].asString(), "cuerpo" to referido)

        vuelta()

        listOf(repartido, referida).forEach { editado ->
            assertTrue(origen.de(editado.pagoId).isEmpty(), "el editado ${editado.pagoId} no se envió")
            val muerto = evento(editado.pagoId)["attributes"]
            assertEquals("MUERTO", muerto["estado"].asString())
            assertTrue(muerto["ultimo_error"].asString().startsWith("el evento no coincide con su recibo"), muerto.toString())
        }
        // un recibo de dos órdenes que nadie tocó se recompone igual y se entrega
        assertEquals("ENTREGADO", evento(intacto.pagoId)["attributes"]["estado"].asString())
    }

    @Test
    fun `una anulacion se entrega, y una con otro pagoOriginalId no se envia`() {
        val supervisor = funcionario("SUPERVISOR_CAJA")
        val anulado = cobrar(PRUEBAS)
        val editado = cobrar(PRUEBAS)
        listOf(anulado, editado).forEach {
            send("POST", "/api/caja/recibos/${it.numero}/anulacion", ANULACION, HttpStatus.CREATED, supervisor)
            origen.contestar(it.pagoId, 200)
        }
        val anulacion = anulacionDe(anulado)
        val editada = anulacionDe(editado)
        origen.contestar(anulacion["attributes"]["evento_id"].asString(), 200)
        origen.contestar(editada["attributes"]["evento_id"].asString(), 200)
        val otroOriginal =
            editada["attributes"]["cuerpo"].asString().replace(editado.pagoId, UUID.randomUUID().toString())
        cambiarEnLaBase("pago_evento", editada["id"].asString(), "cuerpo" to otroOriginal)

        vuelta()

        assertEquals("ENTREGADO", evento(anulado.pagoId)["attributes"]["estado"].asString())
        assertEquals("ENTREGADO", evento(anulacion["attributes"]["evento_id"].asString())["attributes"]["estado"].asString())
        val muerta = evento(editada["attributes"]["evento_id"].asString())["attributes"]
        assertEquals("MUERTO", muerta["estado"].asString())
        assertTrue(muerta["ultimo_error"].asString().contains("pagoOriginalId"), muerta.toString())
        assertTrue(origen.de(editada["attributes"]["evento_id"].asString()).isEmpty())
    }

    // del orden: un PAGO_ANULADO no sale antes que su PAGO_REGISTRADO (caja-backend#23)

    @Test
    fun `un PAGO_ANULADO espera a su PAGO_REGISTRADO sin contar intento, y sale despues de el`() {
        val cobro = cobrar(PRUEBAS)
        // el origen no contesta al pago (503: nadie le dijo nada) y sí contestaría a su anulación
        send("POST", "/api/caja/recibos/${cobro.numero}/anulacion", ANULACION, HttpStatus.CREATED, funcionario("SUPERVISOR_CAJA"))
        val anulacion = anulacionDe(cobro)["attributes"]["evento_id"].asString()
        origen.contestar(anulacion, 200)

        vuelta()

        val pago = evento(cobro.pagoId)["attributes"]
        assertEquals("PENDIENTE", pago["estado"].asString())
        assertEquals(1, pago["intentos"].asInt())
        val esperando = evento(anulacion)["attributes"]
        assertEquals("PENDIENTE", esperando["estado"].asString())
        assertEquals(0, esperando["intentos"].asInt(), "esperar a su pago no es un intento")
        assertTrue(esperando["ultimo_error"].isNull, esperando.toString())
        assertTrue(origen.de(anulacion).isEmpty(), "la anulación no salió antes que su pago")

        origen.contestar(cobro.pagoId, 200)
        vuelta()

        assertEquals("ENTREGADO", evento(cobro.pagoId)["attributes"]["estado"].asString())
        val entregada = evento(anulacion)["attributes"]
        assertEquals("ENTREGADO", entregada["estado"].asString(), "sale en la misma vuelta que su pago")
        assertEquals(1, entregada["intentos"].asInt())
        val pagoEntregado = origen.recibidas.indexOfLast { it.pagoId == cobro.pagoId }
        val anulacionRecibida = origen.recibidas.indexOfFirst { it.pagoId == anulacion }
        assertTrue(pagoEntregado in 0 until anulacionRecibida, "el origen recibe el pago antes que su anulación: $pagoEntregado, $anulacionRecibida")
    }

    @Test
    fun `si su PAGO_REGISTRADO muere, la anulacion no se envia, muere con su motivo y el turno cierra al explicar los dos`(salida: CapturedOutput) {
        val cajero = cuenta("CAJERO")
        val supervisor = funcionario("SUPERVISOR_CAJA")
        val cobro = cobrar(PRUEBAS, cajero = cajero)
        origen.contestar(cobro.pagoId, 422, """{"detail":"rentas no conoce esa orden"}""")
        send("POST", "/api/caja/recibos/${cobro.numero}/anulacion", ANULACION, HttpStatus.CREATED, supervisor)
        val anulacion = anulacionDe(cobro)["attributes"]["evento_id"].asString()
        origen.contestar(anulacion, 200)

        vuelta()

        assertEquals("MUERTO", evento(cobro.pagoId)["attributes"]["estado"].asString())
        val muerta = evento(anulacion)["attributes"]
        assertEquals("MUERTO", muerta["estado"].asString(), "su pago nunca llegó: la anulación no va a salir")
        assertEquals(1, muerta["intentos"].asInt())
        val motivo = muerta["ultimo_error"].asString()
        assertTrue(motivo.contains(cobro.pagoId) && motivo.contains("no se envía"), motivo)
        assertTrue(origen.de(anulacion).isEmpty(), "el origen no recibe la anulación de un pago que no conoce")
        assertTrue(alertaDe(salida, anulacion).contains("PAGO_ANULADO"))

        // los dos impiden cerrar el turno, y los dos se explican
        val cierre = mapOf("caja" to cobro.caja.codigo, "declarado" to mapOf("EFECTIVO" to "0.00"), "observacion" to "cierre del turno")
        val problema = tree(send("POST", CIERRE, cierre, HttpStatus.CONFLICT, cajero.token))
        assertEquals(
            setOf("${cobro.pagoId} PAGO_REGISTRADO MUERTO", "$anulacion PAGO_ANULADO MUERTO"),
            problema["pagos_sin_entregar"].toList().map { "${it["pago_id"].asString()} ${it["tipo"].asString()} ${it["estado"].asString()}" }.toSet()
        )
        listOf(cobro.pagoId, anulacion).forEach {
            send(
                "POST",
                explicar(it),
                mapOf("explicacion" to "rentas nunca registró el pago y el recibo se anuló: nada que anular allí", "observacion" to "conciliado con rentas"),
                HttpStatus.OK,
                supervisor
            )
        }
        assertEquals("CERRADO", post(CIERRE, cierre, cajero.token)["estado_del_turno"].asString())
    }

    @Test
    fun `un valor con saltos de linea no parte la alerta en dos`(salida: CapturedOutput) {
        val cobro = cobrar(PRUEBAS)
        val legitimo = evento(cobro.pagoId)["attributes"]
        val inyectado = UUID.randomUUID().toString()
        forjar(legitimo, inyectado, legitimo["cuerpo"].asString(), sistema = "$PRUEBAS\nDINERO FALSO inyectado")

        vuelta()

        assertEquals("MUERTO", evento(inyectado)["attributes"]["estado"].asString())
        assertTrue(alertaDe(salida, inyectado).contains("DINERO FALSO inyectado"))
        assertFalse(salida.out.lines().any { it.startsWith("DINERO FALSO") }, "una línea inyectada en el registro")
    }

    // del sello de la transacción: created_at = now(), el comienzo de la transacción

    @Test
    fun `todo lo que inserta un cobro, y una anulacion, lleva el sello de su transaccion, y una escritura suelta otro`() {
        val cobro = cobrar(PRUEBAS, importe = "100.00", otros = listOf("50.50"))
        val evento = evento(cobro.pagoId)
        val reciboId = evento["attributes"]["recibo"].asString()
        val recibo = registro("recibo", reciboId)
        val sello = recibo["createdAt"].asString()
        assertEquals(sello, evento["createdAt"].asString(), "el evento nace en la transacción del recibo")
        val lineas = registros("linea_recibo", "recibo" to reciboId)
        assertEquals(2, lineas.size)
        lineas.forEach { assertEquals(sello, it["createdAt"].asString(), "cada línea también") }
        assertEquals(sello, registro("turno", cobro.turnoId)["createdAt"].asString(), "y el turno que abrió")
        // la orden PAGADA es un UPDATE y no un INSERT: desde wasichai 0.5.0 su updated_at lo da el reloj de la sentencia y no el
        // comienzo de la transacción (ADR-051), así que ya no lleva el sello. se marcó dentro de ella, no antes
        lineas.forEach {
            val marcada = Instant.parse(registro("orden_de_cobro", it["attributes"]["orden"].asString())["updatedAt"].asString())
            assertFalse(marcada.isBefore(Instant.parse(sello)), "y cada orden PAGADA se marcó dentro de ella")
        }

        send("POST", "/api/caja/recibos/${cobro.numero}/anulacion", ANULACION, HttpStatus.CREATED, funcionario("SUPERVISOR_CAJA"))
        val acta = registros("anulacion_recibo", "recibo" to reciboId).single()["createdAt"].asString()
        assertEquals(acta, anulacionDe(cobro)["createdAt"].asString(), "el PAGO_ANULADO nace en la transacción de su acta")
        assertTrue(acta != sello)

        // una línea escrita por la API genérica es otra transacción: otro sello
        val suelta = lineaForjada(reciboId, lineas.first()["attributes"]["orden"].asString())
        assertTrue(registro("linea_recibo", suelta)["createdAt"].asString() != sello)
    }

    @Test
    fun `una linea forjada y el cuerpo editado para incluirla no se envian`() {
        val cobro = cobrar(PRUEBAS)
        origen.contestar(cobro.pagoId, 200)
        // otra deuda del mismo sistema, sin pagar, que el forjador quiere dar por cobrada
        val impaga = post(ORDENES, orden("sistema_origen" to PRUEBAS, "importe" to "10.00"))
        val reciboId = evento(cobro.pagoId)["attributes"]["recibo"].asString()
        lineaForjada(reciboId, impaga["orden_id"].asString(), impaga["referencia_externa"].asString(), "10.00")
        val cuerpo = json.readTree(evento(cobro.pagoId)["attributes"]["cuerpo"].asString()) as ObjectNode
        (cuerpo["ordenes"] as ArrayNode)
            .addObject()
            .put("ordenId", impaga["orden_id"].asString())
            .put("referenciaExterna", impaga["referencia_externa"].asString())
            .put("importe", "10.00")
            .put("actualizadoA", "2026-03-15")
        cambiarEnLaBase("pago_evento", evento(cobro.pagoId)["id"].asString(), "cuerpo" to cuerpo.toString())

        vuelta()

        assertTrue(origen.de(cobro.pagoId).isEmpty(), "no se envió")
        val muerto = evento(cobro.pagoId)["attributes"]
        assertEquals("MUERTO", muerto["estado"].asString())
        assertTrue(muerto["ultimo_error"].asString().contains("ordenes"), muerto.toString())
        assertEquals(
            "PENDIENTE",
            tree(send("GET", "/api/objects/orden_de_cobro/records/${impaga["orden_id"].asString()}", null, HttpStatus.OK))["attributes"]["estado"].asString()
        )
    }

    @Test
    fun `una linea forjada despues, o la fecha de la orden cambiada, no matan al evento legitimo`() {
        val cobro = cobrar(PRUEBAS)
        origen.contestar(cobro.pagoId, 200)
        val reciboId = evento(cobro.pagoId)["attributes"]["recibo"].asString()
        val ordenPagada = registros("linea_recibo", "recibo" to reciboId).single()["attributes"]["orden"].asString()
        lineaForjada(reciboId, ordenPagada)
        cambiarEnLaBase("orden_de_cobro", ordenPagada, "actualizado_a" to "2027-01-01")

        vuelta()

        assertEquals("ENTREGADO", evento(cobro.pagoId)["attributes"]["estado"].asString())
        assertEquals(1, origen.de(cobro.pagoId).size)
    }

    @Test
    fun `un PAGO_ANULADO forjado antes no impide que la anulacion legitima se entregue`() {
        val cobro = cobrar(PRUEBAS)
        origen.contestar(cobro.pagoId, 200)
        val legitimo = evento(cobro.pagoId)["attributes"]
        val forjadoId = UUID.randomUUID().toString()
        forjarEnLaBase(
            "pago_evento",
            mapOf(
                "evento_id" to forjadoId,
                "tipo" to "PAGO_ANULADO",
                "sistema_destino" to PRUEBAS,
                "recibo" to legitimo["recibo"].asString(),
                "turno" to legitimo["turno"].asString(),
                "cuerpo" to """{"pagoId":"$forjadoId","tipo":"PAGO_ANULADO"}""",
                "estado" to "PENDIENTE",
                "intentos" to 0
            )
        )
        origen.contestar(forjadoId, 200)
        vuelta()
        assertEquals("MUERTO", evento(forjadoId)["attributes"]["estado"].asString())
        assertEquals("ENTREGADO", evento(cobro.pagoId)["attributes"]["estado"].asString())

        send("POST", "/api/caja/recibos/${cobro.numero}/anulacion", ANULACION, HttpStatus.CREATED, funcionario("SUPERVISOR_CAJA"))
        val anulado = anulacionesDe(cobro).single { it["attributes"]["evento_id"].asString() != forjadoId }["attributes"]["evento_id"].asString()
        origen.contestar(anulado, 200)
        vuelta()

        assertEquals("ENTREGADO", evento(anulado)["attributes"]["estado"].asString())
        assertEquals(1, origen.de(anulado).size)
        assertTrue(origen.de(forjadoId).isEmpty())
    }

    // de los fallos a mitad de la vuelta

    @Test
    fun `un fallo inesperado con un evento cuenta su intento y la vuelta sigue con el siguiente`() {
        val envenenado = cobrar(PRUEBAS)
        val siguiente = cobrar(PRUEBAS)
        origen.contestar(envenenado.pagoId, 200)
        origen.contestar(siguiente.pagoId, 200)
        // la marca ENTREGADO del primero revienta en la base: un fallo que no es del destino
        conFalloAlMarcar(evento(envenenado.pagoId)["id"].asString(), soloAlEntregar = true) { vuelta() }

        val tras = evento(envenenado.pagoId)["attributes"]
        assertEquals("PENDIENTE", tras["estado"].asString())
        assertEquals(1, tras["intentos"].asInt(), "el fallo inesperado cuenta su intento")
        assertTrue(tras["ultimo_error"].asString().contains("Fallo inesperado"), tras.toString())
        assertEquals("ENTREGADO", evento(siguiente.pagoId)["attributes"]["estado"].asString(), "y no atasca a los que siguen")
    }

    @Test
    fun `un pago_evento con un valor que su tipo no admite, escrito en la base antes que uno legitimo, no atasca el buzon`(salida: CapturedOutput) {
        val cobro = cobrar(PRUEBAS)
        origen.contestar(cobro.pagoId, 200)
        val malo = UUID.randomUUID().toString()
        val tabla = runBlocking { tablaDe("pago_evento") }
        val eventoId = runBlocking { columnaDe("pago_evento", "evento_id") }
        // en la base, un ENUM es una columna text con un CHECK de sus opciones, y quien escribe en la base con el dueño de
        // las tablas también lo quita. por SQL crudo, por debajo del almacén de wasichai: una copia del legítimo con un tipo
        // fuera de sus opciones y anterior a él, primero en la cola. no coincide con su recibo y muere, pero el update de
        // core reescribe la fila entera, vuelve a validar cada campo y la rechaza: no se puede marcar, ni de respaldo
        sinElCheckDe("pago_evento", "tipo") {
            val legitimo = evento(cobro.pagoId)["id"].asString()
            runBlocking {
                // columna -> lo que lleva la copia: lo mismo que el legítimo (null), salvo lo que se nombra
                val copia =
                    mapOf(
                        "evento_id" to "'$malo'::uuid",
                        "tipo" to "'pago_registrado'",
                        "sistema_destino" to null,
                        "recibo" to null,
                        "turno" to null,
                        "cuerpo" to null,
                        "estado" to "'PENDIENTE'",
                        "intentos" to "0"
                    ).entries.associate { (campo, valor) -> "\"${columnaDe("pago_evento", campo)}\"".let { it to (valor ?: it) } }
                ejecutar(
                    "INSERT INTO $tabla (organization_id, created_by, updated_by, created_at, updated_at, ${copia.keys.joinToString(", ")}) " +
                        "SELECT organization_id, created_by, updated_by, created_at - interval '1 minute', created_at - interval '1 minute', " +
                        "${copia.values.joinToString(", ")} FROM $tabla WHERE id = '$legitimo'::uuid"
                )
            }
            try {
                vuelta()

                assertEquals("ENTREGADO", evento(cobro.pagoId)["attributes"]["estado"].asString(), "el legítimo sale en la misma vuelta")
                assertFalse(salida.out.contains("no se pudo sacar en esta vuelta"), "la vuelta de la organización no se cortó")
                assertTrue(origen.de(malo).isEmpty(), "el malo no se envió")
                val guardado = evento(malo)["attributes"]
                assertEquals("PENDIENTE", guardado["estado"].asString(), "no se pudo marcar: sigue como estaba")
                assertEquals(0, guardado["intentos"].asInt())
                // una sola línea ERROR que nombra el evento y por qué no se puede anotar
                val error = salida.out.lines().single { it.contains(" ERROR ") && it.contains(malo) }
                assertTrue(error.contains("pago_registrado") && error.contains("tipo must be one of"), error)
            } finally {
                // la base es compartida: la fila mala no queda para las vueltas de las demás pruebas, y el CHECK vuelve
                runBlocking { ejecutar("DELETE FROM $tabla WHERE \"$eventoId\" = '$malo'::uuid") }
            }
        }
    }

    @Test
    fun `la alerta de un pago que murio no se pierde aunque la vuelta se corte despues`(salida: CapturedOutput) {
        val muerto = cobrar(PRUEBAS)
        val despues = cobrar(PRUEBAS)
        origen.contestar(muerto.pagoId, 422)
        origen.contestar(despues.pagoId, 200)
        // ninguna marca del siguiente se puede escribir: la vuelta de la organización se corta ahí
        conFalloAlMarcar(evento(despues.pagoId)["id"].asString(), soloAlEntregar = false) { vuelta() }

        assertEquals("MUERTO", evento(muerto.pagoId)["attributes"]["estado"].asString())
        assertEquals("PENDIENTE", evento(despues.pagoId)["attributes"]["estado"].asString())
        assertEquals(0, evento(despues.pagoId)["attributes"]["intentos"].asInt())
        assertTrue(alertaDe(salida, muerto.pagoId).contains("DINERO COBRADO SIN REGISTRAR"))
    }

    // de la explicación

    @Test
    fun `la explicacion solo funciona con un pago MUERTO`() {
        val supervisor = funcionario("SUPERVISOR_CAJA")
        val pendiente = cobrar(CAIDO)
        val entregado = cobrar(PRUEBAS)
        val rechazado = cobrar(PRUEBAS)
        origen.contestar(entregado.pagoId, 200)
        origen.contestar(rechazado.pagoId, 422)
        vuelta()

        val explicacion = mapOf("explicacion" to "rentas borró la orden; se registró a mano", "observacion" to "lo explica el supervisor")
        listOf(pendiente to "PENDIENTE", entregado to "ENTREGADO").forEach { (cobro, estado) ->
            val problema = tree(send("POST", explicar(cobro.pagoId), explicacion, HttpStatus.CONFLICT, supervisor))
            assertTrue(problema["detail"].asString().contains("MUERTO") && problema["detail"].asString().contains(estado), problema.toString())
            assertEquals(estado, evento(cobro.pagoId)["attributes"]["estado"].asString())
        }

        // los 400, el 404 y el 403, antes de tocar nada
        rejected("POST", explicar(rechazado.pagoId), mapOf("explicacion" to " abc ", "observacion" to "lo explica el supervisor"), "explicacion", supervisor)
        rejected("POST", explicar(rechazado.pagoId), mapOf("explicacion" to "una explicación"), "observacion", supervisor)
        rejected("POST", explicar(rechazado.pagoId), explicacion + ("estado" to "ENTREGADO"), "estado", supervisor)
        rejected("POST", explicar("no-es-un-uuid"), explicacion, "pago_id", supervisor)
        send("POST", explicar(UUID.randomUUID().toString()), explicacion, HttpStatus.NOT_FOUND, supervisor)
        val prohibido = tree(send("POST", explicar(rechazado.pagoId), explicacion, HttpStatus.FORBIDDEN, funcionario("CAJERO")))
        assertTrue(prohibido["detail"].asString().contains("pago_evento"), prohibido.toString())
        assertEquals("MUERTO", evento(rechazado.pagoId)["attributes"]["estado"].asString())

        val explicado = tree(send("POST", explicar(rechazado.pagoId), explicacion, HttpStatus.OK, supervisor))
        assertEquals("EXPLICADO", explicado["estado"].asString())
        assertEquals("rentas borró la orden; se registró a mano", explicado["explicacion"].asString())
        assertEquals(rechazado.numero, explicado["recibo"].asString())
        val guardado = evento(rechazado.pagoId)["attributes"]
        assertEquals("EXPLICADO", guardado["estado"].asString())
        assertEquals("rentas borró la orden; se registró a mano", guardado["explicacion"].asString())

        // la observación de la explicación es la razón de la edición, en la auditoría de core: no hay otra fila aparte
        val id = evento(rechazado.pagoId)["id"].asString()
        val historia = tree(send("GET", "/api/objects/pago_evento/records/$id/history", null, HttpStatus.OK)).toList()
        val edicion = historia.single { it["operation"].asString() == "UPDATE" && it["reason"].asString() == "lo explica el supervisor" }
        assertFalse(edicion["userEmail"].isNull, "la explicó una persona: $edicion")
        assertTrue(edicion["changes"].toList().any { it["field"].asString() == "explicacion" }, edicion.toString())

        // explicado una vez, no se explica otra
        send("POST", explicar(rechazado.pagoId), explicacion, HttpStatus.CONFLICT, supervisor)
    }

    @Test
    fun `elPagoMuertoSeExplicaYEntoncesCierra`() {
        val cajero = cuenta("CAJERO")
        val supervisor = funcionario("SUPERVISOR_CAJA")
        val cobro = cobrar(PRUEBAS, cajero = cajero)
        origen.contestar(cobro.pagoId, 422, """{"detail":"rentas no conoce esa orden"}""")
        vuelta()
        assertEquals("MUERTO", evento(cobro.pagoId)["attributes"]["estado"].asString())

        // la lista de los que no se pudieron entregar
        val lista = tree(send("GET", SIN_ENTREGAR, null, HttpStatus.OK, supervisor))
        val fila = lista.toList().single { it["pago_id"].asString() == cobro.pagoId }
        assertEquals(
            setOf(
                "pago_id",
                "tipo",
                "destino",
                "recibo",
                "turno_id",
                "estado",
                "intentos",
                "ultimo_error",
                "creado_en",
                "entregado_en",
                "explicacion"
            ),
            fila.propertyNames().asSequence().toSet()
        )
        assertEquals("PAGO_REGISTRADO", fila["tipo"].asString())
        assertEquals(PRUEBAS, fila["destino"].asString())
        assertEquals(cobro.numero, fila["recibo"].asString())
        assertEquals(cobro.turnoId, fila["turno_id"].asString())
        assertEquals("MUERTO", fila["estado"].asString())
        assertEquals(1, fila["intentos"].asInt())
        assertTrue(fila["ultimo_error"].asString().contains("rentas no conoce esa orden"))
        assertTrue(fila["creado_en"].asString().endsWith("-05:00"), fila.toString())
        assertTrue(fila["entregado_en"].isNull && fila["explicacion"].isNull)
        assertTrue(lista.toList().all { it["estado"].asString() == "MUERTO" })
        // tesorería lee, quien no lee el buzón no
        send("GET", SIN_ENTREGAR, null, HttpStatus.OK, funcionario("TESORERIA"))
        send("GET", SIN_ENTREGAR, null, HttpStatus.FORBIDDEN, funcionario(listOf(permiso("recibo", "READ"))))

        // un pago MUERTO no deja cerrar
        val cierre = mapOf("caja" to cobro.caja.codigo, "declarado" to mapOf("EFECTIVO" to "150.50"), "observacion" to "cierre del turno")
        val problema = tree(send("POST", CIERRE, cierre, HttpStatus.CONFLICT, cajero.token))
        assertEquals(
            listOf("${cobro.pagoId} PAGO_REGISTRADO MUERTO"),
            problema["pagos_sin_entregar"].toList().map { "${it["pago_id"].asString()} ${it["tipo"].asString()} ${it["estado"].asString()}" }
        )

        send(
            "POST",
            explicar(cobro.pagoId),
            mapOf("explicacion" to "se registró a mano en rentas", "observacion" to "conciliado con rentas"),
            HttpStatus.OK,
            supervisor
        )

        // explicado, sale de la lista y el turno cierra
        assertTrue(tree(send("GET", SIN_ENTREGAR, null, HttpStatus.OK, supervisor)).toList().none { it["pago_id"].asString() == cobro.pagoId })
        assertEquals("CERRADO", post(CIERRE, cierre, cajero.token)["estado_del_turno"].asString())
    }

    @Test
    fun `con el buzon habilitado el bucle corre`() {
        assertTrue(bucle.isRunning)
    }

    // ayudas

    private class Cobro(
        val respuesta: JsonNode,
        val pagoId: String,
        val numero: String,
        val turnoId: String,
        val referencia: String,
        val caja: CajaDePrueba
    )

    // una orden de ese sistema, cobrada en una caja nueva por un cajero
    private fun cobrar(
        sistema: String,
        importe: String = "150.50",
        cajero: Cuenta = cuenta("CAJERO"),
        otros: List<String> = emptyList()
    ): Cobro {
        val caja = nuevaCaja()
        val altas = (listOf(importe) + otros).map { post(ORDENES, orden("sistema_origen" to sistema, "importe" to it)) }
        val alta = altas.first()
        val cobro =
            post(
                COBROS,
                mapOf(
                    "caja" to caja.codigo,
                    "forma_pago" to "EFECTIVO",
                    "ordenes" to altas.map { it["orden_id"].asString() },
                    "observacion" to "cobro en ventanilla"
                ),
                cajero.token
            )
        val pagoId = cobro["pago_id"].asString()
        return Cobro(
            cobro,
            pagoId,
            cobro["recibo"]["numero_impreso"].asString(),
            evento(pagoId)["attributes"]["turno"].asString(),
            alta["referencia_externa"].asString(),
            caja
        )
    }

    private fun evento(pagoId: String): JsonNode = registros("pago_evento", "evento_id" to pagoId).single()

    // el evento de ese cobro como lo lee el publicador, con su buzón
    private suspend fun leidoDe(cobro: Cobro): Pair<BuzonStore.Buzon, EventoDelBuzon> =
        store.buzones().firstNotNullOf { b -> store.pendientes(b, 100000).firstOrNull { it.eventoId == cobro.pagoId }?.let { b to it } }

    // el PAGO_ANULADO del recibo de ese cobro
    private fun anulacionDe(cobro: Cobro): JsonNode = anulacionesDe(cobro).single()

    private fun anulacionesDe(cobro: Cobro): List<JsonNode> =
        registros("pago_evento", "recibo" to evento(cobro.pagoId)["attributes"]["recibo"].asString(), "tipo" to "PAGO_ANULADO")

    // un registro entero, leído como admin por la api de core (con su createdAt y su updatedAt)
    private fun registro(
        objeto: String,
        id: String
    ): JsonNode = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))

    // una linea_recibo escrita en la base en un recibo que ya existe: su id
    private fun lineaForjada(
        reciboId: String,
        ordenId: String,
        referencia: String = "FORJADA-${unico()}",
        monto: String = "0.01"
    ): String =
        forjarEnLaBase(
            "linea_recibo",
            mapOf(
                "recibo" to reciboId,
                "orden" to ordenId,
                "sistema_origen" to PRUEBAS,
                "concepto" to "UNA LÍNEA FORJADA",
                "referencia_externa" to referencia,
                "monto" to monto
            )
        )

    // un pago_evento escrito en la base, junto a uno legítimo: su id de registro
    private fun forjar(
        legitimo: JsonNode,
        eventoId: String,
        cuerpo: String,
        sistema: String = legitimo["sistema_destino"].asString()
    ): String =
        forjarEnLaBase(
            "pago_evento",
            mapOf(
                "evento_id" to eventoId,
                "tipo" to legitimo["tipo"].asString(),
                "sistema_destino" to sistema,
                "recibo" to legitimo["recibo"].asString(),
                "turno" to legitimo["turno"].asString(),
                "cuerpo" to cuerpo,
                "estado" to "PENDIENTE",
                "intentos" to 0
            )
        )

    // mientras corre el bloque, toda marca de ese pago_evento (o solo la de ENTREGADO) revienta en la base: un disparador
    // de prueba sobre su tabla física, que se borra al terminar
    private fun conFalloAlMarcar(
        id: String,
        soloAlEntregar: Boolean,
        bloque: () -> Unit
    ) {
        val disparador = "caja_prueba_falla_${unico()}"
        runBlocking {
            val tabla = tablaDe("pago_evento")
            val estado = columnaDe("pago_evento", "estado")
            val cuando = "OLD.id = '$id'::uuid" + if (soloAlEntregar) " AND NEW.\"$estado\" = 'ENTREGADO'" else ""
            ejecutar(
                "CREATE OR REPLACE FUNCTION public.caja_prueba_falla_marca() RETURNS trigger LANGUAGE plpgsql AS " +
                    "'BEGIN RAISE EXCEPTION ''fallo simulado al marcar el evento''; END'"
            )
            ejecutar("CREATE TRIGGER $disparador BEFORE UPDATE ON $tabla FOR EACH ROW WHEN ($cuando) EXECUTE FUNCTION public.caja_prueba_falla_marca()")
        }
        try {
            bloque()
        } finally {
            runBlocking { ejecutar("DROP TRIGGER IF EXISTS $disparador ON ${tablaDe("pago_evento")}") }
        }
    }

    // mientras corre el bloque, la columna de ese campo no tiene su CHECK (el de las opciones de un ENUM): lo que haría
    // quien escribe en la base con el dueño de las tablas. al terminar vuelve tal cual, así que el bloque deja la columna
    // como la encontró
    private fun sinElCheckDe(
        objeto: String,
        campo: String,
        bloque: () -> Unit
    ) {
        val tabla = runBlocking { tablaDe(objeto) }
        val (nombre, definicion) =
            runBlocking {
                db
                    .sql(
                        "SELECT c.conname::text, pg_get_constraintdef(c.oid) FROM pg_constraint c " +
                            "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey) " +
                            "WHERE c.conrelid = to_regclass(:tabla) AND c.contype = 'c' AND a.attname = :columna"
                    ).bind("tabla", tabla)
                    .bind("columna", columnaDe(objeto, campo))
                    .map { row, _ -> row.get(0, String::class.java)!! to row.get(1, String::class.java)!! }
                    .one()
                    .awaitSingle()
            }
        runBlocking { ejecutar("ALTER TABLE $tabla DROP CONSTRAINT \"$nombre\"") }
        try {
            bloque()
        } finally {
            runBlocking { ejecutar("ALTER TABLE $tabla ADD CONSTRAINT \"$nombre\" $definicion") }
        }
    }

    private suspend fun columnaDe(
        objeto: String,
        campo: String
    ): String =
        db
            .sql(
                "SELECT f.column_name FROM ${schemas.metadata}.custom_fields f JOIN ${schemas.metadata}.custom_objects o ON o.id = f.object_id " +
                    "WHERE o.name = :objeto AND f.name = :campo"
            ).bind("objeto", objeto)
            .bind("campo", campo)
            .map { row, _ -> row.get(0, String::class.java)!! }
            .one()
            .awaitSingle()

    private suspend fun ejecutar(sql: String) {
        db
            .sql(sql)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun vuelta() {
        assertNotNull(runBlocking { publicador.vuelta() }, "la vuelta no tomó el cerrojo del buzón")
    }

    private fun explicar(pagoId: String) = "/api/caja/pagos/$pagoId/explicacion"

    private fun alertaDe(
        salida: CapturedOutput,
        pagoId: String
    ): String =
        salida.out.lines().firstOrNull { it.contains("DINERO COBRADO SIN REGISTRAR") && it.contains(pagoId) }
            ?: error("ninguna alerta nombra el pago $pagoId")

    private suspend fun tablaDe(objeto: String): String =
        schemas.dataTable(
            db
                .sql("SELECT physical_table FROM ${schemas.metadata}.custom_objects WHERE name = :objeto")
                .bind("objeto", objeto)
                .map { row, _ -> row.get(0, String::class.java)!! }
                .one()
                .awaitSingle()
        )

    private suspend fun contar(sql: String): Long =
        db
            .sql(sql)
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one()
            .awaitSingle()

    companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val COBROS = "/api/caja/cobros"
        const val CIERRE = "/api/caja/turnos/cierre"
        const val SIN_ENTREGAR = "/api/caja/pagos/sin-entregar"
        const val PRUEBAS = "buzon-ok"
        const val CON_TOKEN = "buzon-token"
        const val CAIDO = "buzon-caido"
        const val SIN_URL = "buzon-sin-url"
        const val TOKEN = "el-token-secreto-de-servicio"
        val ANULACION = mapOf("motivo" to "COBRO EN DEMASÍA", "observacion" to "el pagador pagó dos veces en ventanilla")

        val origen = SistemaDeOrigenFalso()

        @JvmStatic
        @DynamicPropertySource
        fun destinos(registro: DynamicPropertyRegistry) {
            registro.add("caja.buzon.destinos.$PRUEBAS.url") { origen.url() }
            registro.add("caja.buzon.destinos.$CON_TOKEN.url") { origen.url("con-token") }
            registro.add("caja.buzon.destinos.$CON_TOKEN.token") { TOKEN }
            // nadie escucha en el puerto 1: la conexión se rechaza
            registro.add("caja.buzon.destinos.$CAIDO.url") { "http://127.0.0.1:1" }
        }

        @JvmStatic
        @AfterAll
        fun apagar() = origen.close()
    }
}
