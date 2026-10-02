package caja.turno

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
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

// GET /api/caja/turnos/del-dia y /{turno_id}/arqueo, el cajero y el día del cierre y el reenvío de un cobro
// (ElTurnoYSuArqueoEnVivoTest, TurnoDelDiaFronteraTest y ElCajeroYElDiaSalenDelTokenTest de caja). el reloj de esta
// clase se puede adelantar: el turno de «ayer» es el de hoy visto mañana
class TurnoApiTest : CajaApiTest() {
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

    @AfterEach
    fun relojEnHora() {
        reloj.desfase = Duration.ZERO
    }

    private val hoy: LocalDate get() = LocalDate.now(reloj)

    // del turno del día

    @Test
    fun `el turno abierto de quien pregunta sale entero, con su caja y su hora`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        cobrarTasa(caja, cajero)
        val guardado = registros("turno", "caja" to caja.id).single()

        val delDia = delDia(cajero)

        assertEquals(cajero.email, delDia["cajero"].asString())
        assertEquals(hoy.toString(), delDia["fecha"].asString())
        assertEquals("ABIERTO", delDia["situacion"].asString())
        val turno = delDia["turnos"].single()
        assertEquals(guardado["id"].asString(), turno["turno_id"].asString())
        assertEquals(caja.codigo, turno["caja"].asString())
        assertEquals("VENTANILLA ${caja.codigo}", turno["caja_nombre"].asString())
        assertEquals(cajero.email, turno["cajero"].asString())
        assertEquals(hoy.toString(), turno["fecha"].asString())
        assertEquals("ABIERTO", turno["estado_del_turno"].asString())
        // la hora que se guardó, en Lima: la pantalla de cierre dice desde cuándo arquea
        val abiertoEn = OffsetDateTime.parse(turno["abierto_en"].asString())
        assertEquals(Instant.parse(guardado["attributes"]["abierto_en"].asString()), abiertoEn.toInstant())
        assertEquals(LIMA.rules.getOffset(abiertoEn.toInstant()), abiertoEn.offset)
    }

    @Test
    fun `sin turno es un dato y no un error, y preguntar no abre ninguno`() {
        val cajero = cuenta("CAJERO")

        val delDia = delDia(cajero)
        delDia(cajero)

        assertEquals("SIN_ABRIR", delDia["situacion"].asString())
        assertEquals(0, delDia["turnos"].size())
        assertEquals(0, registros("turno", "cajero" to cajero.email).size, "preguntar no abrió un turno")
    }

    @Test
    fun `el que ya cerro no se confunde con el que no abrio`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        cobrarTasa(caja, cajero)
        post(CIERRE, cierreDe(caja), cajero.token)

        val delDia = delDia(cajero)

        // al que cerró no le falta abrir: le falta reversar su cierre
        assertEquals("CERRADO", delDia["situacion"].asString())
        assertEquals("CERRADO", delDia["turnos"].single()["estado_del_turno"].asString())
    }

    @Test
    fun `con turno abierto en dos ventanillas sale VARIOS_ABIERTOS, y salen los dos por codigo de caja`() {
        val una = nuevaCaja()
        val otra = nuevaCaja()
        val cajero = cuenta("CAJERO")
        cobrarTasa(otra, cajero)
        cobrarTasa(una, cajero)

        val delDia = delDia(cajero)

        assertEquals("VARIOS_ABIERTOS", delDia["situacion"].asString())
        assertEquals(listOf(una.codigo, otra.codigo).sorted(), delDia["turnos"].toList().map { it["caja"].asString() })
    }

    @Test
    fun `el turno del dia no lleva parametros, y cada uno es un 400 que lo nombra`() {
        val cajero = cuenta("CAJERO")

        val problema = tree(send("GET", "$TURNOS/del-dia?cajero=otro@caja.test&fecha=2026-01-01", null, HttpStatus.BAD_REQUEST, cajero.token))

        assertEquals(setOf("cajero", "fecha"), problema["errors"].toList().map { it["field"].asString() }.toSet())
    }

    // del arqueo en vivo

    @Test
    fun `el arqueo en vivo no inventa un declarado`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        cobrarTasa(caja, cajero, cantidad = 3)
        val turnoId = delDia(cajero)["turnos"].single()["turno_id"].asString()

        val enVivo = tree(send("GET", "$TURNOS/$turnoId/arqueo", null, HttpStatus.OK, cajero.token))

        assertEquals(turnoId, enVivo["turno_id"].asString())
        assertEquals("ABIERTO", enVivo["estado_del_turno"].asString())
        assertEquals(true, enVivo["puede_cerrar"].asBoolean())
        assertEquals(0, enVivo["lo_que_impide_cerrar"].size())
        val arqueo = enVivo["arqueo"]
        // lo que sí se sabe sale con su cifra y su fecha (regla 9)
        assertEquals("36.90", arqueo["neto"]["importe"].asString())
        assertEquals(hoy.toString(), arqueo["neto"]["actualizado_a"].asString())
        assertEquals("0.00", enVivo["cobrado_con_evento"]["importe"].asString())
        assertEquals("36.90", enVivo["cobrado_sin_evento"]["importe"].asString())
        // un GET no lleva el recuento del cajón: null, nunca cero
        listOf("total_declarado", "diferencia", "cuadra").forEach { assertTrue(arqueo.has(it) && arqueo[it].isNull, "$it: $arqueo") }
        val linea = arqueo["lineas"].single()
        assertEquals("EFECTIVO", linea["forma_pago"].asString())
        assertEquals("36.90", linea["cobrado"]["importe"].asString())
        listOf("declarado", "diferencia").forEach { assertTrue(linea.has(it) && linea[it].isNull, "$it: $linea") }

        // un turno que no existe es 404; un id que no es un turno, 400
        send("GET", "$TURNOS/${UUID.randomUUID()}/arqueo", null, HttpStatus.NOT_FOUND, cajero.token)
        rejected("GET", "$TURNOS/10/arqueo", null, "turno_id", cajero.token)
    }

    // del cajero y el día

    @Test
    fun `cerrar el turno de otro cajero da 403, y su turno sigue abierto`() {
        val caja = nuevaCaja()
        val ana = cuenta("CAJERO")
        cobrarTasa(caja, ana)

        val problema = tree(send("POST", CIERRE, cierreDe(caja) + ("cajero" to ana.email), HttpStatus.FORBIDDEN, funcionario("CAJERO")))

        assertTrue(problema["detail"].asString().contains(ana.email), problema.toString())
        assertEquals("ABIERTO", delDia(ana)["situacion"].asString())
        // con su propio cajero escrito, cierra igual
        post(CIERRE, cierreDe(caja) + ("cajero" to ana.email), ana.token)
    }

    @Test
    fun `el turno propio de ayer se cierra, y con la fecha de manana es 400`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        cobrarTasa(caja, cajero)
        val ayer = hoy

        // mañana: el turno que se quedó abierto es el de ayer
        reloj.desfase = Duration.ofDays(1)
        rejected("POST", CIERRE, cierreDe(caja) + ("fecha" to hoy.plusDays(1).toString()), "fecha", cajero.token)
        // hoy no abrió ninguno
        send("POST", CIERRE, cierreDe(caja), HttpStatus.NOT_FOUND, cajero.token)

        val cierre = post(CIERRE, cierreDe(caja) + ("fecha" to ayer.toString()), cajero.token)

        assertEquals(ayer.toString(), cierre["fecha"].asString())
        assertEquals("CERRADO", cierre["estado_del_turno"].asString())
        assertEquals(ayer.toString(), cierre["arqueo"]["neto"]["actualizado_a"].asString())
    }

    @Test
    fun `reversar sin SUPERVISOR_CAJA da 403, y el cierre de otro tambien`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        cobrarTasa(caja, cajero)
        post(CIERRE, cierreDe(caja), cajero.token)

        // el CAJERO no tiene CREATE sobre reversion_cierre: no reabre una caja cuyo arqueo ya firmó
        val sinPermiso = tree(send("POST", REVERSION, reversionDe(caja), HttpStatus.FORBIDDEN, cajero.token))
        assertTrue(sinPermiso["detail"].asString().contains("reversion_cierre"), sinPermiso.toString())
        // un supervisor tampoco reversa el turno de otro: nadie actúa sobre el turno de otro cajero
        val ajeno = tree(send("POST", REVERSION, reversionDe(caja) + ("cajero" to cajero.email), HttpStatus.FORBIDDEN, funcionario("SUPERVISOR_CAJA")))
        assertTrue(ajeno["detail"].asString().contains(cajero.email), ajeno.toString())
        assertEquals("CERRADO", delDia(cajero)["situacion"].asString())
    }

    @Test
    fun `cierre y reversion juntan sus 400 y nombran cada campo`() {
        val supervisor = cuenta("SUPERVISOR_CAJA")
        val cierre = tree(send("POST", CIERRE, mapOf("declarado" to mapOf("BITCOIN" to "1.00"), "tributo" to "x"), HttpStatus.BAD_REQUEST, supervisor.token))
        assertEquals(setOf("tributo", "caja", "declarado", "observacion"), cierre["errors"].toList().map { it["field"].asString() }.toSet())
        rejected(
            "POST",
            CIERRE,
            mapOf("caja" to "C-1", "declarado" to mapOf("EFECTIVO" to "-1.00"), "observacion" to "cierre del día"),
            "declarado",
            supervisor.token
        )
        rejected(
            "POST",
            CIERRE,
            mapOf("caja" to "C-1", "declarado" to mapOf("EFECTIVO" to "12,50"), "observacion" to "cierre del día"),
            "declarado",
            supervisor.token
        )
        val reversion = tree(send("POST", REVERSION, mapOf("motivo" to "x".repeat(81)), HttpStatus.BAD_REQUEST, supervisor.token))
        assertEquals(setOf("caja", "motivo", "observacion"), reversion["errors"].toList().map { it["field"].asString() }.toSet())
        // una caja que no existe es 404
        send("POST", CIERRE, mapOf("caja" to "C-NO-EXISTE", "observacion" to "cierre del día"), HttpStatus.NOT_FOUND, supervisor.token)
    }

    // del reenvío

    @Test
    fun `un reenvio al dia siguiente devuelve el recibo sin crear un turno`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = nuevaTasaVigente()
        val clave = mapOf("Idempotency-Key" to UUID.randomUUID().toString())
        val primero = tree(send("POST", TASAS, cobroDeTasa(caja, codigo, 1), HttpStatus.CREATED, cajero.token, clave))

        reloj.desfase = Duration.ofDays(1)
        val reenvio = tree(send("POST", TASAS, cobroDeTasa(caja, codigo, 1), HttpStatus.OK, cajero.token, clave))

        assertEquals(false, reenvio["emitido"].asBoolean())
        assertEquals(primero["recibo"]["numero_impreso"].asString(), reenvio["recibo"]["numero_impreso"].asString())
        assertEquals(1, registros("turno", "caja" to caja.id).size, "el reenvío no abrió un turno vacío")
        assertEquals("SIN_ABRIR", delDia(cajero)["situacion"].asString())
    }

    @Test
    fun `un reenvio despues de dar de baja la caja, o con el turno cerrado, devuelve el recibo de la primera vez`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = nuevaTasaVigente()
        val clave = mapOf("Idempotency-Key" to UUID.randomUUID().toString())
        val primero = tree(send("POST", TASAS, cobroDeTasa(caja, codigo, 1), HttpStatus.CREATED, cajero.token, clave))
        post(CIERRE, cierreDe(caja), cajero.token)
        cambiarComoAdmin("caja", caja.id, "activa" to false)

        val reenvio = tree(send("POST", TASAS, cobroDeTasa(caja, codigo, 1), HttpStatus.OK, cajero.token, clave))

        assertEquals(primero["recibo"].toString(), reenvio["recibo"].toString())
        // un cobro nuevo sí encuentra la caja de baja
        send("POST", TASAS, cobroDeTasa(caja, codigo, 1), HttpStatus.CONFLICT, cajero.token)
    }

    // ayudas

    private fun delDia(cajero: Cuenta): JsonNode = tree(send("GET", "$TURNOS/del-dia", null, HttpStatus.OK, cajero.token))

    private fun nuevaTasaVigente(importe: String = "12.30"): String = codigoDeTasa().also { nuevaTasa(it, importe, hoy.minusDays(1)) }

    private fun cobrarTasa(
        caja: CajaDePrueba,
        cajero: Cuenta,
        cantidad: Int = 1
    ) = post(TASAS, cobroDeTasa(caja, nuevaTasaVigente(), cantidad), cajero.token)

    private fun cobroDeTasa(
        caja: CajaDePrueba,
        codigo: String,
        cantidad: Int
    ) = mapOf(
        "caja" to caja.codigo,
        "forma_pago" to "EFECTIVO",
        "conceptos" to listOf(mapOf("codigo" to codigo, "cantidad" to cantidad)),
        "observacion" to "cobro de tasas en ventanilla"
    )

    private fun cierreDe(caja: CajaDePrueba) = mapOf("caja" to caja.codigo, "observacion" to "cierre del turno")

    private fun reversionDe(caja: CajaDePrueba) = mapOf("caja" to caja.codigo, "motivo" to "ARQUEO MAL CONTADO", "observacion" to "se contó mal el cajón")

    private companion object {
        const val TURNOS = "/api/caja/turnos"
        const val TASAS = "/api/caja/cobros/tasas"
        const val CIERRE = "$TURNOS/cierre"
        const val REVERSION = "$TURNOS/reversion"

        val reloj = RelojMovible()
    }
}
