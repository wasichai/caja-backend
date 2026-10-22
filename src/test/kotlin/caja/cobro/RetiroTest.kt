package caja.cobro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException

// las reglas puras del retiro: el motivo que manda el sistema de origen
class RetiroTest {
    @Test
    fun `el motivo se recorta y va de 1 a 200 caracteres`() {
        assertEquals("Redeterminación por la DJ n° 12", motivoDeRetiro("  Redeterminación por la DJ n° 12 "))
        assertEquals("x", motivoDeRetiro("x"))
        assertEquals("x".repeat(LARGO_MOTIVO_RETIRO), motivoDeRetiro("x".repeat(LARGO_MOTIVO_RETIRO)))
    }

    @Test
    fun `sin motivo, demasiado largo o con caracteres de control es 400 en motivo`() {
        listOf(null, "", "   ", "x".repeat(LARGO_MOTIVO_RETIRO + 1), "con un\u0007timbre", "dos\nlíneas").forEach { valor ->
            val error = assertThrows<ValidationException>(valor.toString()) { motivoDeRetiro(valor) }
            assertEquals("motivo", error.violations.single().field)
        }
    }

    @Test
    fun `el motivo de la sustitucion nombra la nueva y es un motivo valido`() {
        assertEquals("Sustituida por ARB-p1|LIMP|2026|3|1|2", motivoDeSustitucion("ARB-p1|LIMP|2026|3|1|2"))
        assertEquals("Sustituida por ab", motivoDeSustitucion("a\u0007b"))
        val larga = motivoDeSustitucion("x".repeat(LARGO_REFERENCIA))
        assertEquals(larga, motivoDeRetiro(larga))
    }
}
