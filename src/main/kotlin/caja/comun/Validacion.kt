package caja.comun

import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeParseException

// las reglas sobre una petición que no son de ningún acto en particular: las usan el cobro, la anulación, el cierre, el
// buzón y la recaudación. cada una devuelve su valor o lanza el 400 sobre su campo, y campo() junta en un solo 400 todos
// los campos que fallan

// una regla sobre la petición: su valor, o su 400 anotado en errores para juntarlos todos en uno
internal fun <T> campo(
    errores: MutableList<FieldViolation>,
    regla: () -> T
): T? =
    try {
        regla()
    } catch (e: ValidationException) {
        errores += e.violations
        null
    }

// una clave que el cuerpo no lleva (CuerpoEstricto) es un 400 que las nombra todas: callarla dejaría creer que se guardó
fun sinCamposDesconocidos(
    nombres: Collection<String>,
    que: String
) {
    if (nombres.isEmpty()) return
    throw ValidationException("Campo desconocido", nombres.map { FieldViolation(it, "$que no lleva este campo") })
}

// un día del rango, en Lima, o ninguno
fun diaPedido(
    valor: String?,
    campo: String
): LocalDate? {
    val texto = valor?.trim()?.ifEmpty { null } ?: return null
    return try {
        LocalDate.parse(texto)
    } catch (_: DateTimeParseException) {
        throw ValidationException("Fecha inválida", campo, "una fecha AAAA-MM-DD")
    }
}

// el rango va de desde a hasta, los dos incluidos: al revés no hay ningún día que buscar
fun rangoDeDias(
    desde: LocalDate?,
    hasta: LocalDate?
) {
    if (desde != null && hasta != null && desde.isAfter(hasta)) {
        throw ValidationException("Rango al revés", "hasta", "el rango de fechas está al revés: desde $desde hasta $hasta")
    }
}

// el código de la caja que nombra la petición: la que cobra, o la del turno que se cierra o se reversa
fun codigoDeCaja(valor: String?): String =
    valor?.trim()?.ifEmpty { null } ?: throw ValidationException("Falta un dato", "caja", "el código de la caja que cobra")
