package caja.emision

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

// una plantilla de templates/emision a un pdf A4 con la fuente incrustada: las tildes y la ñ sobreviven (de srtm-backend)
class PdfRendererTest {
    private val renderer = PdfRenderer()

    private fun dibujar(model: Map<String, Any?>): ByteArray = runBlocking { renderer.render("prueba", model) }

    @Test
    fun `una plantilla da un pdf`() {
        val pdf = dibujar(mapOf("titulo" to "Recibo", "nombre" to "x"))
        assertEquals("%PDF", String(pdf, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `el texto conserva sus tildes y su enie`() {
        val texto = texto(dibujar(mapOf("titulo" to "Recibo", "nombre" to "PEÑA ÑAUPARI, JOSÉ — Nº 1")))
        assertTrue("PEÑA ÑAUPARI, JOSÉ — Nº 1" in texto, texto)
    }

    @Test
    fun `la pagina es A4`() {
        val pagina = tamanoPagina(dibujar(mapOf("titulo" to "t", "nombre" to "n")))
        // 210 x 297 mm en puntos, con el redondeo
        assertEquals(595.0f, pagina.width, 1.0f)
        assertEquals(842.0f, pagina.height, 1.0f)
    }

    @Test
    fun `la fuente es DejaVu Sans, incrustada`() {
        val nombres = fuentes(dibujar(mapOf("titulo" to "t", "nombre" to "n")))
        assertTrue(nombres.isNotEmpty() && nombres.all { "DejaVuSans" in it }, nombres.toString())
    }

    @Test
    fun `los valores del modelo se escapan`() {
        val texto = texto(dibujar(mapOf("titulo" to "<b>A & B</b>", "nombre" to "n")))
        assertTrue("<b>A & B</b>" in texto, texto)
    }

    // un handler suspend de webflux corre en el hilo que lo despierta: el bucle de eventos de reactor-netty o el de r2dbc.
    // dibujar es CPU sin pausas, y en ese hilo detendría la e/s de todo lo demás que lo comparte. un valor del modelo
    // anota el hilo en el que la plantilla lo lee: el del dibujo
    @Test
    fun `el pdf no se dibuja en el hilo que lo pide`() {
        val leidoEn = AtomicReference<String>()
        val espia =
            object {
                override fun toString(): String = "n".also { leidoEn.set(Thread.currentThread().name) }
            }
        val bucle = Executors.newSingleThreadExecutor { Thread(it, "bucle-de-eventos") }
        try {
            runBlocking(bucle.asCoroutineDispatcher()) { renderer.render("prueba", mapOf("titulo" to "t", "nombre" to espia)) }
        } finally {
            bucle.shutdown()
        }
        assertNotNull(leidoEn.get(), "la plantilla no leyó el valor")
        assertNotEquals("bucle-de-eventos", leidoEn.get())
    }
}
