package caja.recibo

import caja.CajaApiTest
import caja.comun.LIMA
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// POST /api/caja/recibos/{numero}/anulacion: anular es agregar una anulacion_recibo; el recibo no se toca. el mismo día,
// una vez, bajo el candado del turno; las órdenes vuelven a PENDIENTE y el PAGO_ANULADO sale al buzón. el reloj de
// esta clase se puede adelantar: el recibo de «ayer» es el de hoy anulado mañana
class AnulacionApiTest : CajaApiTest() {
    // el reloj de caja con un desfase que la prueba mueve. caja no lee la hora de otro sitio (Relojes)
    class RelojMovible : Clock() {
        @Volatile
        var desfase: Duration = Duration.ZERO

        override fun getZone(): ZoneId = LIMA

        override fun withZone(zone: ZoneId): Clock = system(zone)

        override fun instant(): Instant = Instant.now().plus(desfase)
    }

    @TestConfiguration
    class RelojDePrueba {
        @Bean
        @Primary
        fun relojMovible(): Clock = reloj
    }

    // revienta al encolar el PAGO_ANULADO, cuando el acta y la orden PENDIENTE ya están escritas: si la anulación no
    // fuera una sola transacción, quedarían. solo lo arma la prueba del fallo a mitad
    @TestConfiguration
    class FalloAlEncolarLaAnulacion {
        @Bean
        fun revientaAlEncolarLaAnulacion() =
            object : RecordChangeListener {
                override suspend fun recordChanged(change: RecordChange) {
                    if (!armado.get()) return
                    vistos += "${change.objectName} ${change.kind}"
                    if (change.objectName == "pago_evento" && change.kind == RecordChangeKind.CREATED) {
                        throw IllegalStateException("fallo simulado al encolar el PAGO_ANULADO")
                    }
                }
            }
    }

    @AfterEach
    fun relojEnHora() {
        reloj.desfase = Duration.ZERO
        armado.set(false)
        vistos.clear()
    }

    private val hoy: LocalDate get() = LocalDate.now(LIMA)

    @Test
    fun `la orden vuelve a PENDIENTE, el recibo sigue igual y se agrega el acta`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val cobro = cobrar(caja, cajero)
        val numero = cobro.numero
        val antes = registros("recibo", "numero_impreso" to numero).single()
        val reciboId = antes["id"].asString()
        val lineasAntes = registros("linea_recibo", "recibo" to reciboId)
        val supervisor = cuenta("SUPERVISOR_CAJA")

        val anulado = post(anulacion(numero), PETICION, supervisor.token)

        assertEquals(numero, anulado["numero_impreso"].asString())
        assertEquals(ANULADO, anulado["estado"].asString())
        assertEquals(hoy.toString(), anulado["fecha"].asString())
        assertEquals("COBRO EN DEMASÍA", anulado["motivo"].asString())
        assertEquals("JEFE DE CAJA", anulado["autorizado_por"].asString())
        assertEquals("MEMO 12-2026", anulado["documento_autorizacion"].asString())
        assertEquals(supervisor.email, anulado["usuario"].asString())
        assertEquals("150.50", anulado["importe"]["importe"].asString())
        assertEquals(hoy.toString(), anulado["importe"]["actualizado_a"].asString())

        // la orden vuelve a PENDIENTE y sin recibo: no ANULADA, porque la deuda sigue
        val orden = tree(send("GET", "/api/objects/orden_de_cobro/records/${cobro.ordenId}", null, HttpStatus.OK))["attributes"]
        assertEquals("PENDIENTE", orden["estado"].asString())
        assertTrue(orden["recibo"] == null || orden["recibo"].isNull, orden.toString())

        // el recibo y sus líneas, intactos: ni un campo, ni la hora de su última escritura
        assertEquals(antes, registros("recibo", "numero_impreso" to numero).single())
        assertEquals(lineasAntes, registros("linea_recibo", "recibo" to reciboId))

        // el acta, con la caja y el turno del recibo y su total congelado
        val acta = registros("anulacion_recibo", "recibo" to reciboId).single()["attributes"]
        assertEquals(reciboId, acta["recibo_anulado"].asString())
        assertEquals(caja.id, acta["caja"].asString())
        assertEquals(antes["attributes"]["turno"].asString(), acta["turno"].asString())
        assertEquals(hoy.toString(), acta["fecha"].asString())
        assertEquals(0, acta["importe"].decimalValue().compareTo(java.math.BigDecimal("150.50")))
        assertEquals(supervisor.email, acta["usuario"].asString())
        assertEquals("el pagador pagó dos veces en ventanilla", acta["observacion"].asString())

        // el original ya no se imprime: circularía sin la marca de su anulación
        val original = tree(send("GET", "/api/caja/recibos/$numero/pdf", null, HttpStatus.CONFLICT, cajero.token))
        assertTrue(original["detail"].asString().contains("anulado"), original.toString())

        // y la orden se puede cobrar otra vez
        val otraVez =
            post(
                "/api/caja/cobros",
                mapOf("caja" to caja.codigo, "forma_pago" to "EFECTIVO", "ordenes" to listOf(cobro.ordenId), "observacion" to "cobro otra vez"),
                funcionario("CAJERO")
            )
        assertEquals("${caja.serie}-0000002", otraVez["recibo"]["numero_impreso"].asString())
    }

    @Test
    fun `un fallo a mitad de la anulacion no deja acta, ni orden pendiente, ni PAGO_ANULADO`() {
        val cobro = cobrar(nuevaCaja(), cuenta("CAJERO"))
        val antes = registros("recibo", "numero_impreso" to cobro.numero).single()
        val reciboId = antes["id"].asString()

        armado.set(true)
        send("POST", anulacion(cobro.numero), PETICION, HttpStatus.INTERNAL_SERVER_ERROR, funcionario("SUPERVISOR_CAJA"))
        armado.set(false)

        // el listener vio cada escritura antes de reventar: estaban hechas
        assertEquals(listOf("anulacion_recibo CREATED", "orden_de_cobro UPDATED", "pago_evento CREATED"), vistos.toList())
        // y la transacción se las llevó todas
        assertEquals(0, registros("anulacion_recibo", "recibo" to reciboId).size, "ninguna acta")
        val orden = tree(send("GET", "/api/objects/orden_de_cobro/records/${cobro.ordenId}", null, HttpStatus.OK))["attributes"]
        assertEquals("PAGADA", orden["estado"].asString(), "la orden sigue pagada")
        assertEquals(reciboId, orden["recibo"].asString(), "y sigue nombrando su recibo")
        assertEquals(antes, registros("recibo", "numero_impreso" to cobro.numero).single(), "el recibo, intacto")
        assertEquals(listOf("PAGO_REGISTRADO"), registros("pago_evento", "recibo" to reciboId).map { it["attributes"]["tipo"].asString() })

        // el reintento, ya sin el fallo, anula
        post(anulacion(cobro.numero), PETICION, funcionario("SUPERVISOR_CAJA"))
        assertEquals("PENDIENTE", estadoDe(cobro.ordenId))
    }

    @Test
    fun `se encola PAGO_ANULADO con el pagoId del PAGO_REGISTRADO que deshace`() {
        val cobro = cobrar(nuevaCaja(), cuenta("CAJERO"))

        val anulado = post(anulacion(cobro.numero), PETICION, funcionario("SUPERVISOR_CAJA"))

        val pagoAnuladoId = anulado["pago_anulado_id"].asString()
        val reciboId = registros("recibo", "numero_impreso" to cobro.numero).single()["id"].asString()
        val eventos = registros("pago_evento", "recibo" to reciboId).associateBy { it["attributes"]["tipo"].asString() }
        assertEquals(setOf("PAGO_REGISTRADO", "PAGO_ANULADO"), eventos.keys)
        val evento = eventos.getValue("PAGO_ANULADO")["attributes"]
        assertEquals(pagoAnuladoId, evento["evento_id"].asString())
        assertEquals("PENDIENTE", evento["estado"].asString())
        assertEquals(0L, evento["intentos"].asLong())
        assertEquals("rentas", evento["sistema_destino"].asString())
        assertEquals(eventos.getValue("PAGO_REGISTRADO")["attributes"]["turno"].asString(), evento["turno"].asString())
        val cuerpo = tree(evento["cuerpo"].asString())
        assertEquals(pagoAnuladoId, cuerpo["pagoId"].asString())
        assertEquals("PAGO_ANULADO", cuerpo["tipo"].asString())
        assertEquals(cobro.pagoId, cuerpo["pagoOriginalId"].asString())
        assertEquals(cobro.numero, cuerpo["recibo"]["numero"].asString())
        assertEquals("COBRO EN DEMASÍA", cuerpo["motivo"].asString())
        assertEquals(hoy.toString(), cuerpo["fecha"].asString())
        assertEquals("150.50", cuerpo["total"].asString())
    }

    @Test
    fun `el recibo de ayer no se anula, 422, y no escribe nada`() {
        val cobro = cobrar(nuevaCaja(), cuenta("CAJERO"))

        // mañana, el recibo es de ayer: su turno es de otro día
        reloj.desfase = Duration.ofDays(1)
        val problema = tree(send("POST", anulacion(cobro.numero), PETICION, HttpStatus.UNPROCESSABLE_CONTENT, funcionario("SUPERVISOR_CAJA")))

        assertTrue(problema["detail"].asString().contains("mismo día"), problema.toString())
        val reciboId = registros("recibo", "numero_impreso" to cobro.numero).single()["id"].asString()
        assertEquals(0, registros("anulacion_recibo", "recibo" to reciboId).size)
        assertEquals("PAGADA", estadoDe(cobro.ordenId))
    }

    @Test
    fun `anular dos veces da 409`() {
        val cobro = cobrar(nuevaCaja(), cuenta("CAJERO"))
        val supervisor = funcionario("SUPERVISOR_CAJA")
        post(anulacion(cobro.numero), PETICION, supervisor)

        val problema = tree(send("POST", anulacion(cobro.numero), PETICION, HttpStatus.CONFLICT, supervisor))

        assertTrue(problema["detail"].asString().contains("ya se anuló"), problema.toString())
        val reciboId = registros("recibo", "numero_impreso" to cobro.numero).single()["id"].asString()
        assertEquals(1, registros("anulacion_recibo", "recibo" to reciboId).size)
        assertEquals(2, registros("pago_evento", "recibo" to reciboId).size)
    }

    @Test
    fun `diez anulaciones simultaneas del mismo recibo dan una`() {
        val cobro = cobrar(nuevaCaja(), cuenta("CAJERO"))
        val supervisores = (1..10).map { funcionario("SUPERVISOR_CAJA") }

        val respuestas = simultaneas(supervisores.map { token -> { exchange("POST", anulacion(cobro.numero), PETICION, token) } })

        val estados = respuestas.map { it.first }
        assertEquals(1, estados.count { it == HttpStatus.CREATED }, respuestas.toString())
        assertEquals(9, estados.count { it == HttpStatus.CONFLICT }, respuestas.toString())
        val reciboId = registros("recibo", "numero_impreso" to cobro.numero).single()["id"].asString()
        assertEquals(1, registros("anulacion_recibo", "recibo" to reciboId).size)
        assertEquals(1, registros("pago_evento", "recibo" to reciboId, "tipo" to "PAGO_ANULADO").size)
        assertEquals("PENDIENTE", estadoDe(cobro.ordenId))
    }

    @Test
    fun `un recibo de tasas se anula sin avisar a nadie`() {
        val caja = nuevaCaja()
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        val numero =
            post(
                "/api/caja/cobros/tasas",
                mapOf(
                    "caja" to caja.codigo,
                    "forma_pago" to "EFECTIVO",
                    "conceptos" to listOf(mapOf("codigo" to codigo, "cantidad" to 3)),
                    "observacion" to "cobro de tasas en ventanilla"
                ),
                funcionario("CAJERO")
            )["recibo"]["numero_impreso"].asString()

        val anulado = post(anulacion(numero), PETICION, funcionario("SUPERVISOR_CAJA"))

        assertTrue(anulado["pago_anulado_id"].isNull, anulado.toString())
        assertEquals("36.90", anulado["importe"]["importe"].asString())
        val reciboId = registros("recibo", "numero_impreso" to numero).single()["id"].asString()
        assertEquals(0, registros("pago_evento", "recibo" to reciboId).size)
        assertEquals(1, registros("anulacion_recibo", "recibo" to reciboId).size)
    }

    @Test
    fun `un recibo de ordenes sin su PAGO_REGISTRADO no se anula`() {
        // un recibo NORMAL que nunca avisó su pago (escrito por fuera de la cobranza): anularlo pediría al origen
        // deshacer un pago que no conoce
        val caja = nuevaCaja()
        reciboEscrito(caja, 1, OffsetDateTime.now(LIMA), "12345678")

        val problema = tree(send("POST", anulacion("${caja.serie}-0000001"), PETICION, HttpStatus.CONFLICT, funcionario("SUPERVISOR_CAJA")))

        assertTrue(problema["detail"].asString().contains("PAGO_REGISTRADO"), problema.toString())
    }

    @Test
    fun `sin motivo o sin observacion, 400, y no se anula nada`() {
        val cobro = cobrar(nuevaCaja(), cuenta("CAJERO"))
        val supervisor = funcionario("SUPERVISOR_CAJA")
        val ruta = anulacion(cobro.numero)

        rejected("POST", ruta, PETICION - "motivo", "motivo", supervisor)
        rejected("POST", ruta, PETICION + ("motivo" to "   "), "motivo", supervisor)
        rejected("POST", ruta, PETICION + ("motivo" to "x".repeat(81)), "motivo", supervisor)
        rejected("POST", ruta, PETICION - "observacion", "observacion", supervisor)
        rejected("POST", ruta, PETICION + ("autorizado_por" to "x".repeat(81)), "autorizado_por", supervisor)
        rejected("POST", ruta, PETICION + ("documento_autorizacion" to "x".repeat(41)), "documento_autorizacion", supervisor)
        rejected("POST", ruta, PETICION + ("importe" to "0.00"), "importe", supervisor)
        // todo lo que falla, en un solo 400
        val todos = tree(send("POST", ruta, mapOf("autorizado_por" to "x".repeat(81)), HttpStatus.BAD_REQUEST, supervisor))
        assertEquals(setOf("motivo", "autorizado_por", "observacion"), todos["errors"].toList().map { it["field"].asString() }.toSet())
        rejected("POST", anulacion("abc"), PETICION, "numero_impreso", supervisor)
        send("POST", anulacion("ZZZZZ-9999999"), PETICION, HttpStatus.NOT_FOUND, supervisor)

        val reciboId = registros("recibo", "numero_impreso" to cobro.numero).single()["id"].asString()
        assertEquals(0, registros("anulacion_recibo", "recibo" to reciboId).size)
        assertEquals("PAGADA", estadoDe(cobro.ordenId))
    }

    @Test
    fun `el recibo de otro cajero exige SUPERVISOR_CAJA, y el propio se anula con el permiso`() {
        // un rol que cobra y anula, sin llamarse SUPERVISOR_CAJA
        val rol =
            rolPropio(
                listOf("area", "caja", "tasa", "orden_de_cobro", "turno", "recibo", "linea_recibo", "pago_evento", "anulacion_recibo")
                    .flatMap { listOf(permiso(it, "READ"), permiso(it, "CREATE")) } + permiso("orden_de_cobro", "UPDATE") +
                    // el cobro y la anulación leen la historia del turno: con el turno cerrado no se cobra ni se anula
                    listOf(permiso("cierre_turno", "READ"), permiso("reversion_cierre", "READ"))
            )
        val ana = cuenta(rol)
        val luis = cuenta(rol)
        val caja = nuevaCaja()
        val deAna = cobrar(caja, ana)
        val deLuis = cobrar(caja, luis)

        val problema = tree(send("POST", anulacion(deLuis.numero), PETICION, HttpStatus.FORBIDDEN, ana.token))
        assertTrue(problema["detail"].asString().contains("SUPERVISOR_CAJA"), problema.toString())
        assertEquals("PAGADA", estadoDe(deLuis.ordenId))

        post(anulacion(deAna.numero), PETICION, ana.token)
        assertEquals("PENDIENTE", estadoDe(deAna.ordenId))
    }

    @Test
    fun `un CAJERO, sin CREATE sobre anulacion_recibo, recibe 403`() {
        val cajero = cuenta("CAJERO")
        val cobro = cobrar(nuevaCaja(), cajero)

        val problema = tree(send("POST", anulacion(cobro.numero), PETICION, HttpStatus.FORBIDDEN, cajero.token))

        assertTrue(problema["detail"].asString().contains("anulacion_recibo"), problema.toString())
        assertEquals("PAGADA", estadoDe(cobro.ordenId))
    }

    private class Cobro(
        val numero: String,
        val ordenId: String,
        val pagoId: String
    )

    // cobra una orden nueva de 150.50 en esa caja
    private fun cobrar(
        caja: CajaDePrueba,
        cajero: Cuenta
    ): Cobro {
        val ordenId = post("/api/caja/ordenes-de-cobro", orden())["orden_id"].asString()
        val cobro =
            post(
                "/api/caja/cobros",
                mapOf("caja" to caja.codigo, "forma_pago" to "EFECTIVO", "ordenes" to listOf(ordenId), "observacion" to "cobro en ventanilla"),
                cajero.token
            )
        return Cobro(cobro["recibo"]["numero_impreso"].asString(), ordenId, cobro["pago_id"].asString())
    }

    private fun anulacion(numero: String) = "/api/caja/recibos/$numero/anulacion"

    private fun estadoDe(orden: String): String =
        tree(send("GET", "/api/objects/orden_de_cobro/records/$orden", null, HttpStatus.OK))["attributes"]["estado"].asString()

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
        val reloj = RelojMovible()

        // solo la prueba del fallo a mitad lo arma: el resto del contexto anula como siempre
        val armado = AtomicBoolean(false)
        val vistos: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val PETICION =
            mapOf(
                "motivo" to "COBRO EN DEMASÍA",
                "autorizado_por" to "JEFE DE CAJA",
                "documento_autorizacion" to "MEMO 12-2026",
                "observacion" to "el pagador pagó dos veces en ventanilla"
            )
    }
}
