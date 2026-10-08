package caja.emision

import caja.cobro.lineasEnOrden
import caja.cobro.nombreImpreso
import caja.comun.LIMA
import caja.modelo.Caja
import caja.modelo.LineaRecibo
import caja.modelo.Recibo
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// el recibo en pdf (templates/emision/recibo.html), original o duplicado: lo que el papel dice, congelado en el recibo
// y sus líneas. la hora es la de Lima, los importes van con su moneda y con la fecha a la que están (regla 9). el
// duplicado dice que lo es, con su número, y si el recibo se anuló lo dice también: un duplicado sin marca circula como
// si fuera el original
@Component
class ReciboPdf(
    private val renderer: PdfRenderer,
    private val municipalidad: Municipalidad
) {
    suspend fun original(
        recibo: Recibo,
        caja: Caja,
        lineas: List<LineaRecibo>
    ): ByteArray = renderer.render("recibo", mapOf("r" to impreso(recibo, caja, lineas, ORIGINAL, null)))

    // cual: el número de esta reimpresión, desde 1
    suspend fun duplicado(
        recibo: Recibo,
        caja: Caja,
        lineas: List<LineaRecibo>,
        cual: Int,
        anulado: Anulado?
    ): ByteArray = renderer.render("recibo", mapOf("r" to impreso(recibo, caja, lineas, "DUPLICADO N.° $cual", anulado)))

    private fun impreso(
        recibo: Recibo,
        caja: Caja,
        lineas: List<LineaRecibo>,
        copia: String,
        anulado: Anulado?
    ) = ReciboImpreso(
        copia = copia,
        anulacion = anulado?.let { "Anulado el ${it.fecha.format(FECHA)} — ${it.motivo}" },
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

    // lo que la plantilla imprime, ya escrito. copia: ORIGINAL o DUPLICADO N.° <n>; anulacion: la línea de la
    // anulación, o null
    class ReciboImpreso(
        val copia: String,
        val anulacion: String?,
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

    // la anulación que el duplicado tiene que decir
    class Anulado(
        val fecha: LocalDate,
        val motivo: String
    )

    private companion object {
        const val ORIGINAL = "ORIGINAL"
        val FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val FECHA_HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

        // S/ 1,234.50: el importe exacto (BigDecimal en %f no pasa por coma flotante), con sus dos decimales
        fun soles(importe: BigDecimal): String = "S/ " + String.format(Locale.ROOT, "%,.2f", importe)
    }
}
