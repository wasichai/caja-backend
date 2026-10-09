package caja.modelo

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
import java.math.BigDecimal
import java.time.LocalDate

// caja.modelo es lo que el modelo de caja (model/model.json) comparte entre paquetes: los registros como los guarda
// core, que leen o escriben varios de ellos, y los valores de sus enumerados. lo que solo usa un paquete vive en él.
//
// el catálogo de la municipalidad: sus áreas, sus cajas y las tasas del TUPA

// area y caja como las guarda core. area es el id del área de la caja
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Area(
    val id: String? = null,
    val codigo: String? = null,
    val nombre: String? = null,
    val activa: Boolean? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Caja(
    val id: String? = null,
    val codigo: String? = null,
    val nombre: String? = null,
    val serie: String? = null,
    val activa: Boolean? = null,
    val area: String? = null
)

// una tasa del TUPA en una vigencia, como la guarda core. area es el id de su área. su importe es un dato registrado
// con su documento fuente, nunca un literal (regla 5)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Tasa(
    val id: String? = null,
    val codigo: String? = null,
    val descripcion: String? = null,
    val partidaPresupuestal: String? = null,
    val importe: BigDecimal? = null,
    val vigenciaDesde: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null,
    val documentoFuente: String? = null,
    val area: String? = null
) {
    // Tasa.vigenteA de caja: rige ese día, ambos extremos incluidos; sin vigencia_hasta, no caduca. una vigencia que
    // termina antes de empezar es un dato mal cargado (import_tasas.py la rechaza, el admin no): 409, no se adivina
    fun vigenteA(fecha: LocalDate): Boolean {
        val desde = vigenciaDesde!!
        if (vigenciaHasta != null && vigenciaHasta.isBefore(desde)) {
            throw ConflictException(
                "La vigencia de la tasa $codigo termina antes de empezar ($vigenciaHasta < $desde): es un dato mal " +
                    "cargado, corríjalo en el admin"
            )
        }
        return !fecha.isBefore(desde) && (vigenciaHasta == null || !fecha.isAfter(vigenciaHasta))
    }
}
