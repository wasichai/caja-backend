package caja.emision

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.text.PDFTextStripper

// lo que las pruebas de emisión leen de un pdf (de srtm-backend): su texto, como lo extrae PDFTextStripper, su página y
// las fuentes que incrusta

fun texto(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

fun tamanoPagina(pdf: ByteArray): PDRectangle = Loader.loadPDF(pdf).use { it.getPage(0).mediaBox }

// los nombres de las fuentes que incrusta la primera página (un subconjunto se llama ABCDEF+Familia-Estilo)
fun fuentes(pdf: ByteArray): Set<String> =
    Loader.loadPDF(pdf).use { doc ->
        val recursos = doc.getPage(0).resources
        recursos.fontNames.mapNotNull { recursos.getFont(it)?.name }.toSet()
    }
