package caja.comun

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.LocalDate

// regla 9: toda cifra lleva su fecha. el importe sale como cadena decimal (regla 1: un número json se lee como coma
// flotante del otro lado) y con la fecha a la que está, en iso
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Importe(
    val importe: String,
    val actualizadoA: String
) {
    companion object {
        fun de(
            importe: BigDecimal,
            actualizadoA: LocalDate
        ) = Importe(importe.toPlainString(), actualizadoA.toString())
    }
}

// numeric(15,2) de caja: 13 dígitos enteros y 2 decimales
const val ENTEROS_DEL_IMPORTE = 13

// lo que el alta rechaza de un importe ya leído, o null si vale. el cobro lo vuelve a mirar (motivoNoCobrable): una
// orden escrita en la base no pasó por el alta. GuardiaDeEscrituras lo nombra en el alta que rechaza por la API
// genérica
fun defectoDelImporte(importe: BigDecimal?): String? =
    when {
        importe == null -> "no tiene importe"
        importe.signum() <= 0 -> "debe ser mayor que 0"
        importe.scale() > 2 -> "a lo sumo 2 decimales"
        importe.precision() - importe.scale() > ENTEROS_DEL_IMPORTE -> "a lo sumo $ENTEROS_DEL_IMPORTE dígitos enteros"
        else -> null
    }
