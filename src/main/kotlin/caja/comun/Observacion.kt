package caja.comun

import wasichai.core.common.ValidationException

// regla 10: el por qué de una escritura, escrito por quien la hace. recortada, de 5 a 500 caracteres (code points, como
// los cuenta la plataforma), sin caracteres de control salvo \t, \n y \r: la misma regla que wasichai aplica a la razón,
// así el rechazo sale aquí, sobre `observacion`, y no allá sobre `reason`. un tipo y no un String, para que "" no
// cumpla la regla el día que corre prisa
@JvmInline
value class Observacion private constructor(
    val texto: String
) {
    override fun toString() = texto

    companion object {
        const val MINIMO = 5
        const val MAXIMO = 500

        fun de(texto: String?): Observacion {
            val limpio = texto?.trim().orEmpty()
            val largo = limpio.codePointCount(0, limpio.length)
            if (largo < MINIMO) {
                throw ValidationException(
                    "Falta la observación",
                    "observacion",
                    "explique por qué: al menos $MINIMO caracteres que no sean espacios"
                )
            }
            if (largo > MAXIMO) {
                throw ValidationException("Observación demasiado larga", "observacion", "a lo sumo $MAXIMO caracteres")
            }
            if (limpio.codePoints().anyMatch { Character.isISOControl(it) && it != '\t'.code && it != '\n'.code && it != '\r'.code }) {
                throw ValidationException(
                    "Observación con caracteres de control",
                    "observacion",
                    "sin caracteres de control (solo se admiten tabulador y saltos de línea)"
                )
            }
            return Observacion(limpio)
        }
    }
}
