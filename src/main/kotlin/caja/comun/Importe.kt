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
