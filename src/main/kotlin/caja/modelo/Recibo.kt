package caja.modelo

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// el recibo, sus líneas y su anulación, y los enumerados del recibo: forma_pago y tipo_pago

val FORMAS_DE_PAGO = listOf("EFECTIVO", "CHEQUE", "DEPOSITO", "TARJETA", "TRANSFERENCIA")
const val NORMAL = "NORMAL"

// el tipo_pago de un recibo de tasas. una tasa no produce evento: la emitió esta misma caja, no hubo orden y no hay a
// quién avisarle
const val PAGO_DE_TASA = "TASA"

// TipoDePago.produceEvento de caja: solo un cobro de órdenes avisa a su sistema de origen
fun produceEvento(tipoPago: String): Boolean = tipoPago == NORMAL

// el recibo y su línea como los guarda core. caja, turno, recibo, orden y tasa son ids de relación
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Recibo(
    val id: String? = null,
    val serie: String? = null,
    val numero: Long? = null,
    val numeroImpreso: String? = null,
    val caja: String? = null,
    val turno: String? = null,
    val cajero: String? = null,
    val pagadorDocumento: String? = null,
    val pagadorNombre: String? = null,
    val pagadorExternoId: Long? = null,
    val emitidoEn: Instant? = null,
    val formaPago: String? = null,
    val tipoPago: String? = null,
    val total: BigDecimal? = null,
    val actualizadoA: LocalDate? = null,
    val claveIdempotencia: String? = null,
    val observacion: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LineaRecibo(
    val id: String? = null,
    val recibo: String? = null,
    val orden: String? = null,
    val tasa: String? = null,
    val sistemaOrigen: String? = null,
    val concepto: String? = null,
    val detalle: String? = null,
    val referenciaExterna: String? = null,
    val cantidad: Long? = null,
    val precioUnitario: BigDecimal? = null,
    val monto: BigDecimal? = null
)

// la anulación como la guarda core: un acta que se agrega, y el recibo no se toca. recibo, caja y turno son ids de
// relación
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnulacionRecibo(
    val id: String? = null,
    val recibo: String? = null,
    val reciboAnulado: String? = null,
    val caja: String? = null,
    val turno: String? = null,
    val fecha: LocalDate? = null,
    val motivo: String? = null,
    val autorizadoPor: String? = null,
    val documentoAutorizacion: String? = null,
    val importe: BigDecimal? = null,
    val usuario: String? = null,
    val observacion: String? = null
)
