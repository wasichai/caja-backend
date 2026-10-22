package caja.cobro

import caja.modelo.OrdenDeCobro
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

// el alta que choca (caja-backend#40): qué campos de la deuda difieren entre la orden guardada y la pedida con la
// misma referencia. lo que no es la deuda (el concepto, el nombre, la fecha a la que está el importe) no cuenta
class ChoqueTest {
    private val existente =
        OrdenDeCobro(
            id = "00000000-0000-0000-0000-000000000001",
            sistemaOrigen = "rentas",
            referenciaExterna = "ARB-p1|LIMP|2026|3|1|1",
            importe = BigDecimal("100.00"),
            fechaExigibilidad = LocalDate.parse("2026-03-31"),
            actualizadoA = LocalDate.parse("2026-03-15"),
            pagadorDocumento = "10203040A"
        )

    private fun pedida(vararg cambios: Pair<String, Any?>): Map<String, Any?> =
        mapOf(
            "sistema_origen" to "rentas",
            "referencia_externa" to "ARB-p1|LIMP|2026|3|1|1",
            "importe" to "100.00",
            "fecha_exigibilidad" to "2026-03-31",
            "actualizado_a" to "2026-03-15",
            "pagador_documento" to "10203040A",
            "concepto" to "ARBITRIOS 2026 - MARZO"
        ) + cambios

    @Test
    fun `importe 100,0 contra 100,00 no difiere`() {
        assertEquals(emptyList<String>(), diferencias(existente, pedida("importe" to "100.0")))
        assertEquals(emptyList<String>(), diferencias(existente, pedida("importe" to "100")))
    }

    @Test
    fun `importe 100,01 difiere en importe`() {
        assertEquals(listOf("importe"), diferencias(existente, pedida("importe" to "100.01")))
    }

    @Test
    fun `otra fecha difiere en fecha_exigibilidad`() {
        assertEquals(listOf("fecha_exigibilidad"), diferencias(existente, pedida("fecha_exigibilidad" to "2026-04-30")))
    }

    @Test
    fun `el documento en minusculas no difiere`() {
        assertEquals(emptyList<String>(), diferencias(existente, pedida("pagador_documento" to "10203040a")))
    }

    @Test
    fun `otro documento, o uno que falta, difiere en pagador_documento`() {
        assertEquals(listOf("pagador_documento"), diferencias(existente, pedida("pagador_documento" to "99999999")))
        assertEquals(listOf("pagador_documento"), diferencias(existente, pedida("pagador_documento" to null)))
        assertEquals(emptyList<String>(), diferencias(existente.copy(pagadorDocumento = null), pedida("pagador_documento" to null)))
    }

    @Test
    fun `actualizado_a no se compara`() {
        assertEquals(emptyList<String>(), diferencias(existente, pedida("actualizado_a" to "2026-10-22")))
    }

    @Test
    fun `el concepto y el nombre del pagador no se comparan`() {
        assertEquals(emptyList<String>(), diferencias(existente, pedida("concepto" to "otro texto", "pagador_nombre" to "OTRO")))
    }

    @Test
    fun `varios campos salen en el orden de la deuda`() {
        assertEquals(
            listOf("importe", "fecha_exigibilidad", "pagador_documento"),
            diferencias(existente, pedida("importe" to "1.00", "fecha_exigibilidad" to "2026-01-01", "pagador_documento" to "X"))
        )
    }

    @Test
    fun `sustituye_a se compara solo si la pedida lo trae`() {
        val sustituta = existente.copy(sustituyeA = "ARB-v1")
        assertEquals(emptyList<String>(), diferencias(sustituta, pedida()))
        assertEquals(emptyList<String>(), diferencias(sustituta, pedida("sustituye_a" to "ARB-v1")))
        assertEquals(listOf("sustituye_a"), diferencias(sustituta, pedida("sustituye_a" to "ARB-v0")))
        assertEquals(listOf("sustituye_a"), diferencias(existente, pedida("sustituye_a" to "ARB-v1")))
    }
}
