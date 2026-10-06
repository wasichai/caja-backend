package caja.modelo

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.time.Instant
import java.time.LocalDate

// el turno: la apertura de una caja por un cajero en un día

// el turno como lo guarda core. caja es el id de relación
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Turno(
    val id: String? = null,
    val caja: String? = null,
    val cajero: String? = null,
    val fecha: LocalDate? = null,
    val abiertoEn: Instant? = null,
    val observacion: String? = null,
    val claveTurno: String? = null
)

// la clave del turno de un cajero en una caja un día (clave_turno): un cajero tiene un solo turno al día por caja. el
// cobro lo busca o lo crea con ella, el cierre y la reversión lo buscan
fun claveDelTurno(
    cajaId: String,
    cajero: String,
    fecha: LocalDate
): String = "$cajaId|$cajero|$fecha"
