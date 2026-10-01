package caja.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// una plantilla de templates/emision a un pdf A4 con la fuente incrustada: las tildes y la ñ sobreviven (de srtm-backend)
class PdfRendererTest {
    private val renderer = PdfRenderer()

    @Test
    fun `una plantilla da un pdf`() {
        val pdf = renderer.render("prueba", mapOf("titulo" to "Recibo", "nombre" to "x"))
        assertEquals("%PDF", String(pdf, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `el texto conserva sus tildes y su enie`() {
        val texto = texto(renderer.render("prueba", mapOf("titulo" to "Recibo", "nombre" to "PEÑA ÑAUPARI, JOSÉ — Nº 1")))
        assertTrue("PEÑA ÑAUPARI, JOSÉ — Nº 1" in texto, texto)
    }

    @Test
    fun `la pagina es A4`() {
        val pagina = tamanoPagina(renderer.render("prueba", mapOf("titulo" to "t", "nombre" to "n")))
        // 210 x 297 mm en puntos, con el redondeo
        assertEquals(595.0f, pagina.width, 1.0f)
        assertEquals(842.0f, pagina.height, 1.0f)
    }

    @Test
    fun `la fuente es DejaVu Sans, incrustada`() {
        val nombres = fuentes(renderer.render("prueba", mapOf("titulo" to "t", "nombre" to "n")))
        assertTrue(nombres.isNotEmpty() && nombres.all { "DejaVuSans" in it }, nombres.toString())
    }

    @Test
    fun `los valores del modelo se escapan`() {
        val texto = texto(renderer.render("prueba", mapOf("titulo" to "<b>A & B</b>", "nombre" to "n")))
        assertTrue("<b>A & B</b>" in texto, texto)
    }
}
