package caja.comun

import wasichai.core.common.ValidationException

// regla 10: el por qué de una escritura, escrito por quien la hace. recortada, de 5 a 500 caracteres: un tipo y no
// un String, para que "" no cumpla la regla el día que corre prisa
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
            if (limpio.length < MINIMO) {
                throw ValidationException(
                    "Falta la observación",
                    "observacion",
                    "explique por qué: al menos $MINIMO caracteres que no sean espacios"
                )
            }
            if (limpio.length > MAXIMO) {
                throw ValidationException("Observación demasiado larga", "observacion", "a lo sumo $MAXIMO caracteres")
            }
            return Observacion(limpio)
        }
    }
}
