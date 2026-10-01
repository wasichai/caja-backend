package caja.emision

import caja.cobro.Caja
import caja.cobro.LineaRecibo
import caja.cobro.Recibo
import caja.cobro.lineasEnOrden
import caja.cobro.nombreImpreso
import caja.comun.LIMA
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

// el original del recibo en pdf (templates/emision/recibo.html): lo que el papel dice, congelado en el recibo y sus
// líneas. la hora es la de Lima, los importes van con su moneda y con la fecha a la que están (regla 9)
@Component
class ReciboPdf(
    private val renderer: PdfRenderer,
    private val municipalidad: Municipalidad
) {
    fun original(
        recibo: Recibo,
        caja: Caja,
        lineas: List<LineaRecibo>
    ): ByteArray = renderer.render("recibo", mapOf("r" to impreso(recibo, caja, lineas)))

    private fun impreso(
        recibo: Recibo,
        caja: Caja,
        lineas: List<LineaRecibo>
    ) = ReciboImpreso(
        municipalidad = municipalidad.nombre,
        numeroImpreso = recibo.numeroImpreso!!,
        emitido = recibo.emitidoEn!!.atZone(LIMA).format(FECHA_HORA),
        caja = "${caja.codigo} — ${caja.nombre}",
        cajero = recibo.cajero!!,
        pagador = nombreImpreso(recibo.pagadorNombre, recibo.pagadorDocumento),
        documento = recibo.pagadorDocumento?.takeIf { recibo.pagadorNombre != null },
        lineas = lineasEnOrden(lineas).map { LineaImpresa(it.concepto!!, it.detalle, it.referenciaExterna, soles(it.monto!!)) },
        total = soles(recibo.total!!),
        actualizadoA = recibo.actualizadoA!!.format(FECHA),
        formaPago = recibo.formaPago!!,
        observacion = recibo.observacion!!
    )

    // lo que la plantilla imprime, ya escrito
    class ReciboImpreso(
        val municipalidad: String,
        val numeroImpreso: String,
        val emitido: String,
        val caja: String,
        val cajero: String,
        val pagador: String,
        val documento: String?,
        val lineas: List<LineaImpresa>,
        val total: String,
        val actualizadoA: String,
        val formaPago: String,
        val observacion: String
    )

    class LineaImpresa(
        val concepto: String,
        val detalle: String?,
        val referencia: String?,
        val monto: String
    )

    private companion object {
        val FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val FECHA_HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

        // S/ 1,234.50: el importe exacto (BigDecimal en %f no pasa por coma flotante), con sus dos decimales
        fun soles(importe: BigDecimal): String = "S/ " + String.format(Locale.ROOT, "%,.2f", importe)
    }
}
