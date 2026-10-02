package caja.turno

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ForbiddenException
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// lo que llega en las peticiones del turno (CierreController y QuienYCuando de caja): cada regla devuelve el valor o
// lanza su 400 sobre el campo, o el 403 del cajero
class TurnosTest {
    private val hoy = LocalDate.of(2026, 3, 15)

    @Test
    fun `la fecha del turno es hoy o un dia pasado, nunca uno futuro`() {
        assertEquals(hoy, fechaDelTurno(null, hoy))
        assertEquals(hoy, fechaDelTurno("  ", hoy))
        assertEquals(hoy, fechaDelTurno("2026-03-15", hoy))
        // el turno que se quedó abierto ayer tiene que poder cerrarse
        assertEquals(LocalDate.of(2026, 3, 14), fechaDelTurno("2026-03-14", hoy))
        assertEquals("fecha", campo { fechaDelTurno("2026-03-16", hoy) })
        assertEquals("fecha", campo { fechaDelTurno("15/03/2026", hoy) })
    }

    @Test
    fun `lo declarado es un decimal sin signo por forma de pago, y lo que falta cuenta como cero`() {
        assertEquals(emptyMap<String, BigDecimal>(), declaradoPedido(null))
        assertEquals(
            mapOf("EFECTIVO" to BigDecimal("120.50"), "TARJETA" to BigDecimal("0")),
            declaradoPedido(mapOf("EFECTIVO" to " 120.50 ", "tarjeta" to "0", "CHEQUE" to null, "DEPOSITO" to ""))
        )
        // tal como viene: sin redondeo ni escala inventada
        assertEquals("120.5", declaradoPedido(mapOf("EFECTIVO" to "120.5")).getValue("EFECTIVO").toPlainString())
    }

    @Test
    fun `un declarado mal escrito es 400 en declarado, y dice cual`() {
        listOf("12,50", "abc", "-1.00", "1e3", "1.005", "12345678901234.00").forEach { malo ->
            val error = assertThrows<ValidationException> { declaradoPedido(mapOf("EFECTIVO" to malo)) }
            assertEquals("declarado", error.violations.single().field, malo)
            assertTrue(
                error.violations
                    .single()
                    .message
                    .contains("EFECTIVO"),
                error.violations.toString()
            )
        }
        val desconocida = assertThrows<ValidationException> { declaradoPedido(mapOf("BITCOIN" to "1.00", "VALE" to "2.00")) }
        assertEquals(listOf("declarado", "declarado"), desconocida.violations.map { it.field })
        assertTrue(desconocida.violations[0].message.contains("BITCOIN"), desconocida.violations.toString())
        // la misma forma dos veces, escrita distinto, también es un error: ¿cuál de las dos cuenta?
        assertEquals("declarado", campo { declaradoPedido(mapOf("EFECTIVO" to "1.00", "efectivo" to "2.00")) })
    }

    @Test
    fun `reversar exige su motivo, no en blanco y de hasta 80`() {
        assertEquals("ARQUEO MAL CONTADO", motivoDeReversion("  ARQUEO MAL CONTADO "))
        assertEquals("motivo", campo { motivoDeReversion(null) })
        assertEquals("motivo", campo { motivoDeReversion("   ") })
        assertEquals("motivo", campo { motivoDeReversion("x".repeat(81)) })
        assertEquals(80, motivoDeReversion("x".repeat(80)).length)
    }

    @Test
    fun `el cajero es quien firma la sesion, y otro es 403`() {
        assertEquals("ana@caja.test", cajeroDelTurno(null, "ana@caja.test"))
        assertEquals("ana@caja.test", cajeroDelTurno(" ana@caja.test ", "ana@caja.test"))
        val error = assertThrows<ForbiddenException> { cajeroDelTurno("luis@caja.test", "ana@caja.test") }
        assertTrue(error.message.contains("luis@caja.test"), error.message)
    }

    @Test
    fun `el turno del dia no lleva parametros, y cada uno es un 400 que lo nombra`() {
        sinParametros(emptyList())
        val error = assertThrows<ValidationException> { sinParametros(listOf("cajero", "fecha")) }
        assertEquals(listOf("cajero", "fecha"), error.violations.map { it.field })
    }

    @Test
    fun `el id de un turno es un uuid, o 400 en turno_id`() {
        assertEquals("3e6da681-2467-48e6-acc7-281903b9b578", turnoIdPedido(" 3e6da681-2467-48e6-acc7-281903b9b578 "))
        assertEquals("turno_id", campo { turnoIdPedido("10") })
    }

    // el campo del 400 que lanza la regla
    private fun campo(regla: () -> Any?): String = assertThrows<ValidationException> { regla() }.violations.single().field
}
