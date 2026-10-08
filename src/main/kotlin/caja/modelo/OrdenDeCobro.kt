package caja.modelo

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.LocalDate

// la orden de cobro, lo único que la caja sabe cobrar, y estado_orden: PENDIENTE al nacer, PAGADA con el recibo que
// la cobró, ANULADA si su sistema de origen la retiró

const val PENDIENTE = "PENDIENTE"
const val PAGADA = "PAGADA"
const val ANULADA = "ANULADA"
val ESTADOS_DE_ORDEN = listOf(PENDIENTE, PAGADA, ANULADA)

// una orden de cobro como la guarda core. recibo es el id del que la cobró: PAGADA lo nombra (orden_recibo_ck)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrdenDeCobro(
    val id: String? = null,
    val sistemaOrigen: String? = null,
    val referenciaExterna: String? = null,
    val concepto: String? = null,
    val detalle: String? = null,
    val importe: BigDecimal? = null,
    val fechaExigibilidad: LocalDate? = null,
    val actualizadoA: LocalDate? = null,
    val pagadorDocumento: String? = null,
    val pagadorNombre: String? = null,
    val pagadorExternoId: Long? = null,
    val estado: String? = null,
    val observacion: String? = null,
    val recibo: String? = null
) {
    // OrdenDeCobro.cobrableA de caja: pendiente y ya exigible a la fecha de pago
    fun cobrableA(fecha: LocalDate): Boolean = estado == PENDIENTE && fechaExigibilidad != null && !fecha.isBefore(fechaExigibilidad)
}
