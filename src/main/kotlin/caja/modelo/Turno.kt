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
    val observacion: String? = null
)

// la clave del candado TURNO_CLAVE: un cajero tiene un solo turno al día por caja. el cobro lo busca o lo crea bajo ese
// candado, y la garantía es la uniqueConstraint (caja, cajero, fecha) del turno; no se guarda en ningún campo
fun claveDelTurno(
    cajaId: String,
    cajero: String,
    fecha: LocalDate
): String = "$cajaId|$cajero|$fecha"

// los filtros que encuentran el turno de un cajero en una caja un día: los tres campos de su uniqueConstraint. el cobro,
// el cierre, la reversión y la consulta lo buscan así
fun filtroDelTurno(
    cajaId: String,
    cajero: String,
    fecha: LocalDate
): Map<String, String> = mapOf("caja" to cajaId, "cajero" to cajero, "fecha" to fecha.toString())
