package caja.emision

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.slf4j.Slf4jLogger
import com.openhtmltopdf.util.XRLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

// copiado de srtm-backend (srtm.emision.PdfRenderer), sin su cabecera institucional y solo con DejaVu Sans.
// una plantilla de templates/emision (thymeleaf standalone: la app es webflux, no hay capa de vistas mvc) a un pdf con
// openhtmltopdf. el tamaño de página (A4) y los márgenes son del css de la plantilla: templates/emision/base.css, que
// cada plantilla incrusta por la variable "css". la fuente es DejaVu Sans, incrustada (subconjunto) para que las
// tildes y la ñ se impriman igual en todas partes. segura entre hilos: el motor guarda las plantillas leídas, el
// builder es por llamada.
//
// A DIFERENCIA DE srtm, render es suspend y dibuja en Dispatchers.Default: dibujar es CPU sin pausas (decenas de
// milisegundos; el primero, más), y un handler suspend de webflux corre en el hilo que lo despierta, el bucle de eventos
// de reactor-netty o el de r2dbc. dibujar ahí detenía la e/s de las demás peticiones y conexiones de ese bucle, también
// la de un cobro que espera con sus candados tomados
@Component
class PdfRenderer {
    init {
        // openhtmltopdf registra por java.util.logging a stderr: por slf4j sigue logging.level
        XRLog.setLoggerImpl(Slf4jLogger())
    }

    private val engine =
        TemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/emision/"
                    suffix = ".html"
                    templateMode = TemplateMode.HTML
                    characterEncoding = "UTF-8"
                    isCacheable = true
                }
            )
        }

    private val css = recurso("templates/emision/base.css").toString(Charsets.UTF_8)

    private val fuentes =
        listOf(
            Fuente(recurso("fonts/DejaVuSans.ttf"), 400),
            Fuente(recurso("fonts/DejaVuSans-Bold.ttf"), 700)
        )

    // `template` es el nombre del archivo bajo templates/emision, sin .html. los valores del modelo se escapan (th:text)
    suspend fun render(
        template: String,
        model: Map<String, Any?>
    ): ByteArray = withContext(Dispatchers.Default) { dibujar(template, model) }

    private fun dibujar(
        template: String,
        model: Map<String, Any?>
    ): ByteArray {
        // openhtmltopdf lee xhtml: las plantillas están bien formadas, pero prettier escribe el doctype de html5 en
        // minúsculas
        val html = engine.process(template, Context(ES, model + ("css" to css))).replaceFirst(DOCTYPE, "<!DOCTYPE html>")
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .apply { fuentes.forEach { f -> useFont({ ByteArrayInputStream(f.bytes) }, FAMILIA, f.peso, FontStyle.NORMAL, true) } }
            .withHtmlContent(html, null)
            .toStream(out)
            .run()
        return out.toByteArray()
    }

    private class Fuente(
        val bytes: ByteArray,
        val peso: Int
    )

    private companion object {
        // base.css la nombra
        const val FAMILIA = "DejaVu Sans"
        val ES: Locale = Locale.forLanguageTag("es-PE")
        val DOCTYPE = Regex("^\\s*<!doctype html>", RegexOption.IGNORE_CASE)

        fun recurso(ruta: String): ByteArray =
            PdfRenderer::class.java.classLoader
                .getResourceAsStream(ruta)
                ?.use { it.readBytes() }
                ?: error("falta el recurso $ruta")
    }
}
