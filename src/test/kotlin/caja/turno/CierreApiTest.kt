package caja.turno

import caja.CajaApiTest
import caja.comun.LIMA
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// POST /api/caja/turnos/cierre y /reversion (CierreDeCajaJdbcTest y CerrarYArquearTest de caja): el día completo cuadra
// céntimo a céntimo, con el turno cerrado no se cobra ni se anula, reversar reabre, un pago sin entregar impide cerrar,
// un cierre no se modifica y se reversa una vez, dos cierres a la vez dan uno y NADA SE CUELA EN UN CIERRE EN CURSO. el
// buzón está apagado en este contexto: aquí los pagos se marcan ENTREGADO como admin, como lo haría el publicador
// (BuzonApiTest lo prueba con el de verdad, y el pago MUERTO que se explica)
class CierreApiTest : CajaApiTest() {
    // retiene el cierre en curso justo después de escribir su cierre_turno, con el candado del turno tomado y sin
    // confirmar: lo que llegue a ese turno mientras tanto tiene que esperar. solo lo arma la prueba del cierre en curso
    @TestConfiguration
    class CierreRetenido {
        @Bean
        fun retieneElCierre() =
            object : RecordChangeListener {
                override suspend fun recordChanged(change: RecordChange) {
                    val retencion = retenido.get() ?: return
                    if (change.objectName == "cierre_turno" && change.kind == RecordChangeKind.CREATED) {
                        retencion.tomado.complete(Unit)
                        retencion.soltar.await()
                    }
                }
            }
    }

    class Retencion {
        val tomado = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
    }

    @AfterEach
    fun soltarElCierre() {
        retenido.getAndSet(null)?.soltar?.complete(Unit)
    }

    private val hoy: LocalDate get() = LocalDate.now(LIMA)

    // del día completo

    @Test
    fun `un dia completo cuadra centimo a centimo, con cobros de ordenes, tasas y una anulacion`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val constancia = codigoDeTasa()
        val copia = codigoDeTasa()
        nuevaTasa(constancia, "12.30", hoy.minusDays(1))
        nuevaTasa(copia, "0.10", hoy.minusDays(1))

        cobrarOrden(caja, cajero, "EFECTIVO", "150.50")
        val enTarjeta = cobrarOrden(caja, cajero, "TARJETA", "80.25")
        cobrarTasa(caja, cajero, constancia, 3, "EFECTIVO")
        cobrarTasa(caja, cajero, copia, 7, "TRANSFERENCIA")
        post("/api/caja/recibos/${enTarjeta.numero}/anulacion", ANULACION, funcionario("SUPERVISOR_CAJA"))
        val turnoId = turnoDe(caja, cajero)
        // el publicador entrega los dos PAGO_REGISTRADO y el PAGO_ANULADO
        assertEquals(3, entregarLosPagos(turnoId))

        // el arqueo en vivo: 150.50 + 36.90 en efectivo, 80.25 en tarjeta que entró y salió, 0.70 por transferencia
        val enVivo = tree(send("GET", "/api/caja/turnos/$turnoId/arqueo", null, HttpStatus.OK, cajero.token))
        assertEquals(true, enVivo["puede_cerrar"].asBoolean(), enVivo.toString())
        val arqueo = enVivo["arqueo"]
        assertEquals(
            listOf("EFECTIVO 187.40 0.00 187.40", "TARJETA 80.25 80.25 0.00", "TRANSFERENCIA 0.70 0.00 0.70"),
            arqueo["lineas"].toList().map { "${it["forma_pago"].asString()} ${cifra(it["cobrado"])} ${cifra(it["anulado"])} ${cifra(it["neto"])}" }
        )
        assertEquals("268.35", cifra(arqueo["total_cobrado"]))
        assertEquals("80.25", cifra(arqueo["total_anulado"]))
        assertEquals("188.10", cifra(arqueo["neto"]))
        assertEquals(4, arqueo["recibos_emitidos"].asInt())
        assertEquals(1, arqueo["recibos_anulados"].asInt())
        // el cuadre: lo que avisa al origen (la orden que quedó) y lo que no (las tasas) suman el neto
        assertEquals("150.50", cifra(enVivo["cobrado_con_evento"]))
        assertEquals("37.60", cifra(enVivo["cobrado_sin_evento"]))

        val cierre = post(CIERRE, cierreDe(caja, "EFECTIVO" to "187.40", "TRANSFERENCIA" to "0.70"), cajero.token)

        assertEquals("CERRADO", cierre["estado_del_turno"].asString())
        assertEquals(turnoId, cierre["turno_id"].asString())
        assertEquals(1L, cierre["secuencia"].asLong())
        assertEquals(hoy.toString(), cierre["fecha"].asString())
        assertEquals(cajero.email, cierre["usuario"].asString())
        val firmado = cierre["arqueo"]
        assertEquals("188.10", cifra(firmado["neto"]))
        assertEquals("188.10", cifra(firmado["total_declarado"]))
        assertEquals("0.00", cifra(firmado["diferencia"]))
        assertEquals(true, firmado["cuadra"].asBoolean())
        assertEquals(
            listOf("EFECTIVO 187.40 0.00", "TARJETA 0.00 0.00", "TRANSFERENCIA 0.70 0.00"),
            firmado["lineas"].toList().map { "${it["forma_pago"].asString()} ${cifra(it["declarado"])} ${cifra(it["diferencia"])}" }
        )
        assertEquals("150.50", cifra(cierre["cobrado_con_evento"]))
        assertEquals("37.60", cifra(cierre["cobrado_sin_evento"]))

        // lo guardado: el acta con sus totales congelados y una línea por forma de pago
        val acta = registros("cierre_turno", "turno" to turnoId).single()
        val guardado = acta["attributes"]
        assertEquals(cierre["cierre_id"].asString(), acta["id"].asString())
        mapOf(
            "total_cobrado" to "268.35",
            "total_anulado" to "80.25",
            "neto" to "188.10",
            "total_declarado" to "188.10",
            "diferencia" to "0.00",
            "cobrado_con_evento" to "150.50",
            "cobrado_sin_evento" to "37.60"
        ).forEach { (campo, valor) -> assertEquals(0, guardado[campo].decimalValue().compareTo(valor.toBigDecimal()), "$campo: $guardado") }
        assertEquals(4L, guardado["recibos_emitidos"].asLong())
        assertEquals(1L, guardado["recibos_anulados"].asLong())
        assertEquals("$turnoId|1", guardado["clave_secuencia"].asString())
        val lineas = registros("cierre_turno_linea", "cierre_turno" to acta["id"].asString()).map { it["attributes"] }
        assertEquals(setOf("EFECTIVO", "TARJETA", "TRANSFERENCIA"), lineas.map { it["forma_pago"].asString() }.toSet())
        lineas.forEach { assertEquals("${acta["id"].asString()}|${it["forma_pago"].asString()}", it["clave"].asString()) }
    }

    // del turno cerrado

    @Test
    fun `con el turno cerrado no se cobra ni se anula, y reversar lo reabre y se sigue cobrando`() {
        val caja = nuevaCaja()
        // reversar es de SUPERVISOR_CAJA, y cada uno reversa su propio turno
        val cajero = cuenta("SUPERVISOR_CAJA")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        val numero = cobrarTasa(caja, cajero, codigo, 3, "EFECTIVO")

        // sin declarar nada: el descuadre se guarda, no se rechaza
        val cierre = post(CIERRE, cierreDe(caja), cajero.token)
        assertEquals("-36.90", cifra(cierre["arqueo"]["diferencia"]))
        assertEquals(false, cierre["arqueo"]["cuadra"].asBoolean())
        val turnoId = cierre["turno_id"].asString()
        val actaAntes = registros("cierre_turno", "turno" to turnoId).single()

        // ni una tasa, ni una orden, ni una anulación, ni el original
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        listOf(
            send("POST", TASAS, cobroDeTasa(caja, codigo, 1), HttpStatus.CONFLICT, cajero.token),
            send("POST", COBROS, cobroDeOrden(caja, ordenId), HttpStatus.CONFLICT, cajero.token),
            send("POST", "/api/caja/recibos/$numero/anulacion", ANULACION, HttpStatus.CONFLICT, funcionario("SUPERVISOR_CAJA")),
            send("GET", "/api/caja/recibos/$numero/pdf", null, HttpStatus.CONFLICT, cajero.token)
        ).forEach { assertTrue(tree(it)["detail"].asString().contains("urno cerrado"), it) }
        assertEquals(1, registros("recibo", "caja" to caja.id).size)
        assertEquals("PENDIENTE", estadoDe(ordenId))
        assertEquals(0, registros("anulacion_recibo", "turno" to turnoId).size)
        // cerrarlo otra vez es 409: dos arqueos vigentes sobre el mismo dinero
        val otraVez = tree(send("POST", CIERRE, cierreDe(caja), HttpStatus.CONFLICT, cajero.token))
        assertTrue(otraVez["detail"].asString().contains("ya está cerrado"), otraVez.toString())

        // reversar reabre
        val reversion = post(REVERSION, reversionDe(caja), cajero.token)
        assertEquals("ABIERTO", reversion["estado_del_turno"].asString())
        assertEquals(2L, reversion["secuencia"].asLong())
        assertEquals(cierre["cierre_id"].asString(), reversion["cierre_revertido"].asString())
        assertEquals("ARQUEO MAL CONTADO", reversion["motivo"].asString())
        val guardada = registros("reversion_cierre", "turno" to turnoId).single()["attributes"]
        assertEquals("$turnoId|2", guardada["clave_secuencia"].asString())
        assertEquals(cajero.email, guardada["usuario"].asString())

        // y se sigue cobrando en el mismo turno: el cierre nuevo lo incluye, con la secuencia siguiente
        post(COBROS, cobroDeOrden(caja, ordenId), cajero.token)
        assertEquals(1, registros("turno", "caja" to caja.id).size)
        entregarLosPagos(turnoId)
        val segundo = post(CIERRE, cierreDe(caja, "EFECTIVO" to "187.40"), cajero.token)
        assertEquals(3L, segundo["secuencia"].asLong())
        assertEquals(2, segundo["arqueo"]["recibos_emitidos"].asInt())
        assertEquals("0.00", cifra(segundo["arqueo"]["diferencia"]))
        // el primero sigue diciendo lo que decía
        assertEquals(actaAntes, registros("cierre_turno", "turno" to turnoId).single { it["id"] == actaAntes["id"] })
    }

    // de lo que bloquea

    @Test
    fun `un pago PENDIENTE impide cerrar, el arqueo y el 409 dicen cual, y entregado deja cerrar`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val cobro = cobrarOrden(caja, cajero, "EFECTIVO", "150.50")
        val turnoId = turnoDe(caja, cajero)

        val enVivo = tree(send("GET", "/api/caja/turnos/$turnoId/arqueo", null, HttpStatus.OK, cajero.token))
        assertEquals(false, enVivo["puede_cerrar"].asBoolean())
        assertEquals("ABIERTO", enVivo["estado_del_turno"].asString())
        val impide = enVivo["lo_que_impide_cerrar"].single()
        assertEquals(cobro.pagoId, impide["pago_id"].asString())
        assertEquals("PAGO_REGISTRADO", impide["tipo"].asString())
        assertEquals("PENDIENTE", impide["estado"].asString())

        val problema = tree(send("POST", CIERRE, cierreDe(caja, "EFECTIVO" to "150.50"), HttpStatus.CONFLICT, cajero.token))
        assertTrue(problema["detail"].asString().contains("pagos sin entregar"), problema.toString())
        assertTrue(problema["detail"].asString().contains(cobro.pagoId), problema.toString())
        assertEquals(
            listOf("${cobro.pagoId} PAGO_REGISTRADO PENDIENTE"),
            problema["pagos_sin_entregar"].toList().map { "${it["pago_id"].asString()} ${it["tipo"].asString()} ${it["estado"].asString()}" }
        )
        assertEquals(0, registros("cierre_turno", "turno" to turnoId).size)

        entregarLosPagos(turnoId)
        assertEquals(true, tree(send("GET", "/api/caja/turnos/$turnoId/arqueo", null, HttpStatus.OK, cajero.token))["puede_cerrar"].asBoolean())
        post(CIERRE, cierreDe(caja, "EFECTIVO" to "150.50"), cajero.token)
    }

    // de la inmutabilidad

    @Test
    fun `ninguna ruta modifica un cierre, y un cierre se reversa una sola vez`() {
        val caja = nuevaCaja()
        val cajero = cuenta("SUPERVISOR_CAJA")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        cobrarTasa(caja, cajero, codigo, 1, "EFECTIVO")
        val cierre = post(CIERRE, cierreDe(caja, "EFECTIVO" to "12.30"), cajero.token)
        val cierreId = cierre["cierre_id"].asString()
        val turnoId = cierre["turno_id"].asString()
        val linea = registros("cierre_turno_linea", "cierre_turno" to cierreId).single()["id"].asString()

        // la api de caja no tiene ruta que lo cambie ni lo borre
        listOf("PUT", "DELETE").forEach { metodo ->
            val (estado, cuerpo) = exchange(metodo, CIERRE, if (metodo == "PUT") cierreDe(caja) else null, cajero.token)
            assertTrue(estado == HttpStatus.METHOD_NOT_ALLOWED || estado == HttpStatus.NOT_FOUND, "$metodo $CIERRE: $estado $cuerpo")
        }
        // y por la api genérica de core, ningún rol de caja puede: roles.json no da UPDATE ni DELETE
        listOf(cajero.token, funcionario("CAJERO")).forEach { quien ->
            listOf("cierre_turno" to cierreId, "cierre_turno_linea" to linea).forEach { (objeto, id) ->
                val ruta = "/api/objects/$objeto/records/$id"
                assertEquals(HttpStatus.FORBIDDEN, exchange("PUT", ruta, mapOf("attributes" to mapOf("observacion" to "otra")), quien).first, ruta)
                assertEquals(HttpStatus.FORBIDDEN, exchange("DELETE", ruta, null, quien).first, ruta)
            }
        }

        // se reversa una vez; la segunda no tiene nada que reversar
        val reversion = post(REVERSION, reversionDe(caja), cajero.token)
        val problema = tree(send("POST", REVERSION, reversionDe(caja), HttpStatus.CONFLICT, cajero.token))
        assertTrue(problema["detail"].asString().contains("Nada que reversar"), problema.toString())
        val ruta = "/api/objects/reversion_cierre/records/${reversion["reversion_id"].asString()}"
        assertEquals(HttpStatus.FORBIDDEN, exchange("DELETE", ruta, null, cajero.token).first)
        // y aunque alguien la escribiera por fuera de caja, el unique de cierre_revertido es la red
        val (estado, cuerpo) =
            exchange(
                "POST",
                "/api/objects/reversion_cierre/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "turno" to turnoId,
                            "cierre_revertido" to cierreId,
                            "secuencia" to 9,
                            "motivo" to "OTRA VEZ",
                            "fecha" to hoy.toString(),
                            "registrado_en" to OffsetDateTime.now(LIMA).toString(),
                            "usuario" to "admin",
                            "observacion" to "escrita por fuera de caja",
                            "clave_secuencia" to "$turnoId|9"
                        )
                )
            )
        assertFalse(estado.is2xxSuccessful, "$estado $cuerpo")
        assertEquals(1, registros("reversion_cierre", "turno" to turnoId).size)
        assertEquals(1, registros("cierre_turno", "turno" to turnoId).size)
    }

    // de la concurrencia

    @Test
    fun `dos cierres simultaneos dan uno, el segundo espera al candado y encuentra el turno cerrado`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        cobrarTasa(caja, cajero, codigo, 1, "EFECTIVO")

        // el primero escribe su acta y se queda dentro, con el candado del turno; el segundo llega entonces. sin el
        // candado, el segundo leería un turno abierto y chocaría con el unique de clave_secuencia («a la vez»): la red,
        // no el candado
        val retencion = Retencion().also { retenido.set(it) }
        val hilos = Executors.newFixedThreadPool(2)
        try {
            val primero = hilos.submit<Pair<HttpStatus, String>> { exchange("POST", CIERRE, cierreDe(caja, "EFECTIVO" to "12.30"), cajero.token) }
            runBlocking { withTimeout(30_000) { retencion.tomado.await() } }
            val segundo = hilos.submit<Pair<HttpStatus, String>> { exchange("POST", CIERRE, cierreDe(caja, "EFECTIVO" to "12.30"), cajero.token) }
            Thread.sleep(1_500)
            assertFalse(segundo.isDone, "el segundo espera al primero")

            retencion.soltar.complete(Unit)
            assertEquals(HttpStatus.CREATED, primero.get(60, TimeUnit.SECONDS).first)
            val (estado, cuerpo) = segundo.get(60, TimeUnit.SECONDS)
            assertEquals(HttpStatus.CONFLICT, estado, cuerpo)
            assertTrue(tree(cuerpo)["detail"].asString().contains("ya está cerrado"), cuerpo)
        } finally {
            retencion.soltar.complete(Unit)
            hilos.shutdownNow()
        }
        assertEquals(1, registros("cierre_turno", "turno" to turnoDe(caja, cajero)).size)
    }

    @Test
    fun `ocho cierres a la vez dan uno, y los demas encuentran el turno cerrado`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        cobrarTasa(caja, cajero, codigo, 1, "EFECTIVO")
        val turnoId = turnoDe(caja, cajero)

        val respuestas = simultaneas((1..8).map { { exchange("POST", CIERRE, cierreDe(caja, "EFECTIVO" to "12.30"), cajero.token) } })

        val estados = respuestas.map { it.first }
        assertEquals(1, estados.count { it == HttpStatus.CREATED }, respuestas.toString())
        assertEquals(7, estados.count { it == HttpStatus.CONFLICT }, respuestas.toString())
        // los que esperaban al candado del turno leyeron el cierre ya confirmado: «ya está cerrado», no un choque del
        // unique de clave_secuencia, que sería la red y no el candado
        respuestas.filter { it.first == HttpStatus.CONFLICT }.forEach {
            assertTrue(
                tree(it.second)["detail"].asString().contains("ya está cerrado"),
                it.second
            )
        }
        assertEquals(1, registros("cierre_turno", "turno" to turnoId).size)
    }

    // del cierre en curso

    @Test
    fun `con un cierre en curso, un cobro de tasa, uno de orden y una anulacion esperan y reciben 409, y la caja vecina no espera`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        cobrarTasa(caja, cajero, codigo, 1, "EFECTIVO")
        val aAnular = cobrarTasa(caja, cajero, codigo, 1, "EFECTIVO")
        val ordenIntrusa = post(ORDENES, orden())["orden_id"].asString()
        val supervisor = funcionario("SUPERVISOR_CAJA")
        val vecina = nuevaCaja()
        val cajeroVecino = cuenta("CAJERO")

        val retencion = Retencion().also { retenido.set(it) }
        val hilos = Executors.newFixedThreadPool(4)
        try {
            val cierre = hilos.submit<Pair<HttpStatus, String>> { exchange("POST", CIERRE, cierreDe(caja, "EFECTIVO" to "24.60"), cajero.token) }
            runBlocking { withTimeout(30_000) { retencion.tomado.await() } }

            // el cierre escribió su acta y sigue dentro, con el candado del turno tomado: lo que llega ahora espera
            val intrusos: List<Future<Pair<HttpStatus, String>>> =
                listOf(
                    hilos.submit<Pair<HttpStatus, String>> { exchange("POST", TASAS, cobroDeTasa(caja, codigo, 2), cajero.token) },
                    hilos.submit<Pair<HttpStatus, String>> { exchange("POST", COBROS, cobroDeOrden(caja, ordenIntrusa), cajero.token) },
                    hilos.submit<Pair<HttpStatus, String>> { exchange("POST", "/api/caja/recibos/$aAnular/anulacion", ANULACION, supervisor) }
                )
            // la caja vecina es otro turno: no espera a nadie
            val (deLaVecina, cuerpo) = exchange("POST", TASAS, cobroDeTasa(vecina, codigo, 1), cajeroVecino.token)
            assertEquals(HttpStatus.CREATED, deLaVecina, cuerpo)
            Thread.sleep(1_500)
            assertEquals(listOf(false, false, false), intrusos.map { it.isDone }, "los tres esperan al candado del turno")
            assertFalse(cierre.isDone)

            retencion.soltar.complete(Unit)
            val (cerrado, acta) = cierre.get(60, TimeUnit.SECONDS)
            assertEquals(HttpStatus.CREATED, cerrado, acta)
            intrusos.map { it.get(60, TimeUnit.SECONDS) }.forEach { (estado, respuesta) ->
                assertEquals(HttpStatus.CONFLICT, estado, respuesta)
                assertTrue(tree(respuesta)["detail"].asString().contains("Turno cerrado"), respuesta)
            }
        } finally {
            retencion.soltar.complete(Unit)
            hilos.shutdownNow()
        }

        // el acta dice lo que hay: los dos recibos, ninguno anulado, y nada más entró en el turno cerrado
        val turnoId = turnoDe(caja, cajero)
        val acta = registros("cierre_turno", "turno" to turnoId).single()["attributes"]
        assertEquals(2L, acta["recibos_emitidos"].asLong())
        assertEquals(0L, acta["recibos_anulados"].asLong())
        assertEquals(0, acta["neto"].decimalValue().compareTo("24.60".toBigDecimal()), acta.toString())
        assertEquals(2, registros("recibo", "caja" to caja.id).size)
        assertEquals(0, registros("anulacion_recibo", "turno" to turnoId).size)
        assertEquals(0, registros("pago_evento", "turno" to turnoId).size)
        assertEquals("PENDIENTE", estadoDe(ordenIntrusa))
    }

    // ayudas

    private class Cobro(
        val numero: String,
        val pagoId: String
    )

    private fun cobrarOrden(
        caja: CajaDePrueba,
        cajero: Cuenta,
        forma: String,
        importe: String
    ): Cobro {
        val ordenId = post(ORDENES, orden("importe" to importe))["orden_id"].asString()
        val cobro = post(COBROS, cobroDeOrden(caja, ordenId, forma), cajero.token)
        return Cobro(cobro["recibo"]["numero_impreso"].asString(), cobro["pago_id"].asString())
    }

    // el número del recibo
    private fun cobrarTasa(
        caja: CajaDePrueba,
        cajero: Cuenta,
        codigo: String,
        cantidad: Int,
        forma: String
    ): String = post(TASAS, cobroDeTasa(caja, codigo, cantidad, forma), cajero.token)["recibo"]["numero_impreso"].asString()

    private fun cobroDeOrden(
        caja: CajaDePrueba,
        ordenId: String,
        forma: String = "EFECTIVO"
    ) = mapOf("caja" to caja.codigo, "forma_pago" to forma, "ordenes" to listOf(ordenId), "observacion" to "cobro en ventanilla")

    private fun cobroDeTasa(
        caja: CajaDePrueba,
        codigo: String,
        cantidad: Int,
        forma: String = "EFECTIVO"
    ) = mapOf(
        "caja" to caja.codigo,
        "forma_pago" to forma,
        "conceptos" to listOf(mapOf("codigo" to codigo, "cantidad" to cantidad)),
        "observacion" to "cobro de tasas en ventanilla"
    )

    private fun cierreDe(
        caja: CajaDePrueba,
        vararg declarado: Pair<String, String>
    ) = mapOf("caja" to caja.codigo, "declarado" to declarado.toMap(), "observacion" to "cierre del turno de la mañana")

    private fun reversionDe(caja: CajaDePrueba) = mapOf("caja" to caja.codigo, "motivo" to "ARQUEO MAL CONTADO", "observacion" to "se contó mal el cajón")

    private fun turnoDe(
        caja: CajaDePrueba,
        cajero: Cuenta
    ): String = registros("turno", "caja" to caja.id).single { it["attributes"]["cajero"].asString() == cajero.email }["id"].asString()

    // lo que haría el publicador del buzón: cada pago PENDIENTE del turno, ENTREGADO. cuántos
    private fun entregarLosPagos(turnoId: String): Int {
        val pendientes = registros("pago_evento", "turno" to turnoId, "estado" to "PENDIENTE")
        pendientes.forEach {
            cambiarComoAdmin("pago_evento", it["id"].asString(), "estado" to "ENTREGADO", "entregado_en" to OffsetDateTime.now(LIMA).toString())
        }
        return pendientes.size
    }

    private fun estadoDe(orden: String): String =
        tree(send("GET", "/api/objects/orden_de_cobro/records/$orden", null, HttpStatus.OK))["attributes"]["estado"].asString()

    private fun cifra(importe: JsonNode): String {
        assertNotEquals(true, importe.isNull, "una cifra sin valor")
        return importe["importe"].asString()
    }

    // las llamadas a la vez, cada una en su hilo, soltadas juntas
    private fun simultaneas(llamadas: List<() -> Pair<HttpStatus, String>>): List<Pair<HttpStatus, String>> {
        val salida = CyclicBarrier(llamadas.size)
        val hilos = Executors.newFixedThreadPool(llamadas.size)
        return try {
            llamadas
                .map { llamada ->
                    hilos.submit<Pair<HttpStatus, String>> {
                        salida.await(30, TimeUnit.SECONDS)
                        llamada()
                    }
                }.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            hilos.shutdownNow()
        }
    }

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val COBROS = "/api/caja/cobros"
        const val TASAS = "/api/caja/cobros/tasas"
        const val CIERRE = "/api/caja/turnos/cierre"
        const val REVERSION = "/api/caja/turnos/reversion"

        val retenido = AtomicReference<Retencion?>(null)

        val ANULACION = mapOf("motivo" to "COBRO EN DEMASÍA", "observacion" to "el pagador pagó dos veces en ventanilla")
    }
}
