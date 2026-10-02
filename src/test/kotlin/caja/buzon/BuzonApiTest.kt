package caja.buzon

import caja.CajaApiTest
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
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// el publicador del buzón contra un sistema de origen falso por http (CobrarConElOrigenApagadoTest,
// CadaEventoEnSuTransaccionTest y UnPagoNoMuereSinCredencialTest de caja), los pagos sin entregar, su explicación y la
// defensa frente a un pago_evento inventado por la API genérica. el buzón está encendido, pero su bucle espera una hora:
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
            val (buzon, leido) =
                store.buzones().firstNotNullOf { b -> store.pendientes(b, 100000).firstOrNull { it.eventoId == cobro.pagoId }?.let { b to it } }

            publicador.entregarUno(buzon, leido)
            publicador.entregarUno(buzon, leido)

            assertEquals(2, origen.de(cobro.pagoId).size, "los dos llamaron")
            assertEquals(1, evento(cobro.pagoId)["attributes"]["intentos"].asInt(), "y se contó uno")
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

    // de la segunda puerta

    @Test
    fun `un pago_evento creado por la API generica no se envia, muere y salta el detector`(salida: CapturedOutput) {
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
        val cajero = cuenta("CAJERO")
        val inventado =
            tree(
                send(
                    "POST",
                    "/api/objects/pago_evento/records",
                    mapOf(
                        "attributes" to
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
                    ),
                    HttpStatus.CREATED,
                    cajero.token
                )
            )["id"].asString()

        // el detector: la escritura no pasó por la api de caja
        val detectado = salida.out.lines().firstOrNull { it.contains("ESCRITURA FUERA DE CAJA") && it.contains(inventado) }
        assertNotNull(detectado, "el detector no vio la escritura")
        assertTrue(detectado!!.contains(" ERROR ") && detectado.contains("pago_evento"), detectado)

        vuelta()

        assertTrue(origen.de(inventadoId).isEmpty(), "no se envió")
        val muerto = evento(inventadoId)["attributes"]
        assertEquals("MUERTO", muerto["estado"].asString())
        assertTrue(muerto["ultimo_error"].asString().startsWith("el evento no coincide con su recibo"), muerto.toString())
        assertTrue(alertaDe(salida, inventadoId).contains("DINERO COBRADO SIN REGISTRAR"))
        // el legítimo, en la misma vuelta, sí
        assertEquals("ENTREGADO", evento(cobro.pagoId)["attributes"]["estado"].asString())
        // y las escrituras de caja no las ve el detector
        assertFalse(salida.out.lines().any { it.contains("ESCRITURA FUERA DE CAJA") && it.contains(legitimo["recibo"].asString()) })
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
        cajero: Cuenta = cuenta("CAJERO")
    ): Cobro {
        val caja = nuevaCaja()
        val alta = post(ORDENES, orden("sistema_origen" to sistema, "importe" to importe))
        val cobro =
            post(
                COBROS,
                mapOf(
                    "caja" to caja.codigo,
                    "forma_pago" to "EFECTIVO",
                    "ordenes" to listOf(alta["orden_id"].asString()),
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
