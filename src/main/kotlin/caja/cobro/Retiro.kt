package caja.cobro

import wasichai.core.common.ValidationException

// las reglas puras del retiro de una orden por su sistema de origen (caja-backend#39)

const val LARGO_MOTIVO_RETIRO = 200

// la razón de la escritura del retiro en la auditoría: un texto fijo. el motivo es del origen y va en motivo_retiro; la
// razón no lo repite, para que nunca lleve lo que el origen escriba
const val RAZON_RETIRO = "retiro de la orden por su sistema de origen"

// el motivo del retiro, recortado: obligatorio, de 1 a 200 caracteres (code points, como los cuenta la plataforma) y
// sin caracteres de control, ni saltos de línea: es una línea que se lee en ventanilla y en el 422 del cobro
fun motivoDeRetiro(valor: String?): String {
    val motivo = valor?.trim()?.ifEmpty { null } ?: throw ValidationException("Falta el motivo", "motivo", "es obligatorio: por qué se retira")
    if (motivo.codePointCount(0, motivo.length) > LARGO_MOTIVO_RETIRO) {
        throw ValidationException("Motivo demasiado largo", "motivo", "a lo sumo $LARGO_MOTIVO_RETIRO caracteres")
    }
    if (motivo.codePoints().anyMatch(Character::isISOControl)) {
        throw ValidationException("Motivo con caracteres de control", "motivo", "una sola línea, sin caracteres de control")
    }
    return motivo
}

// la razón de la escritura que anota sustituida_por en la orden reemplazada (caja-backend#40): un texto fijo, como la
// del retiro
const val RAZON_SUSTITUCION = "sustitución de la orden por una versión nueva de su sistema de origen"

// el motivo del retiro de la orden que una versión nueva reemplaza: «Sustituida por {nueva}». la referencia es opaca y
// puede traer caracteres de control, que motivo_retiro no admite (es una línea del 422 del cobro): se quitan. cabe en
// los 200: la referencia es de 120 a lo sumo
fun motivoDeSustitucion(nueva: String): String = "Sustituida por " + nueva.filterNot(Char::isISOControl)
