package caja.comun

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ImporteTest {
    @Test
    fun `el importe sale como vino, en cadena llana, con su fecha iso`() {
        assertEquals(Importe("10080.45", "2026-03-15"), Importe.de(BigDecimal("10080.45"), LocalDate.of(2026, 3, 15)))
        assertEquals("1000", Importe.de(BigDecimal("1E+3"), LocalDate.of(2026, 3, 15)).importe)
        assertEquals("12.50", Importe.de(BigDecimal("12.50"), LocalDate.of(2026, 3, 15)).importe)
    }
}
