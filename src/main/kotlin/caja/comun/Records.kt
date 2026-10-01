package caja.comun

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

// los nombres de los objetos de model/model.json
const val AREA = "area"
const val CAJA = "caja"
const val TASA = "tasa"
const val ORDEN_DE_COBRO = "orden_de_cobro"

// los atributos de un registro <-> un dto de caja. las claves json de los dtos son los nombres de los campos, así que
// jackson hace el mapeo: core devuelve DATE como cadena iso (LocalDate aquí), DECIMAL como BigDecimal, INTEGER como
// Long y una relación como el id en cadena. un campo que el dto no conoce (agregado en el admin) se ignora al leer
object Records {
    private val json: JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    fun <T : Any> read(
        type: Class<T>,
        id: String,
        attributes: Map<String, Any?>
    ): T = json.convertValue(attributes + ("id" to id), type)

    inline fun <reified T : Any> read(
        id: String,
        attributes: Map<String, Any?>
    ): T = read(T::class.java, id, attributes)

    // cada campo del dto, null incluido: el update de core reemplaza todo. Registros manda solo los que el usuario
    // puede escribir
    @Suppress("UNCHECKED_CAST")
    fun attributes(dto: Any): Map<String, Any?> = (json.convertValue(dto, Map::class.java) as Map<String, Any?>) - "id"
}
