package caja.comun

import org.springframework.stereotype.Component
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

// el RecordService de wasichai con los dtos de caja encima (portado de srtm-backend). RecordService comprueba los
// permisos de objeto y de campo del usuario que llama y valida cada escritura, así que aquí no se repite. no abre
// transacción: cada escritura se confirma sola, y un unique que salta llega como DuplicateKeyException. un dto lleva
// todos sus campos, y core rechaza la escritura entera que nombra un campo que el usuario no puede escribir: las
// escrituras mandan solo los escribibles
@Component
class Registros(
    private val records: RecordService,
    private val metadata: MetadataService
) {
    suspend fun <T : Any> page(
        objectName: String,
        type: Class<T>,
        query: RecordQuery
    ): PageResponse<T> {
        val result = records.list(objectName, query)
        return PageResponse(result.content.map { read(type, it) }, result.page, result.size, result.totalElements, result.totalPages)
    }

    // cada registro que coincide, página por página: para los pocos de un pagador y los catálogos
    suspend fun <T : Any> all(
        objectName: String,
        type: Class<T>,
        filters: Map<String, String> = emptyMap(),
        sort: String? = null,
        descending: Boolean = false
    ): List<T> {
        val rows = mutableListOf<RecordResponse>()
        var page = 0
        do {
            val result =
                records.list(
                    objectName,
                    RecordQuery(page = PageRequest.of(page, PageRequest.MAX_SIZE), sort = sort, descending = descending, filters = filters)
                )
            rows += result.content
            page++
        } while (page < result.totalPages)
        return rows.map { read(type, it) }
    }

    suspend fun <T : Any> get(
        objectName: String,
        type: Class<T>,
        id: UUID
    ): T = read(type, records.get(objectName, id))

    // los registros detrás de un conjunto de ids de relación: una consulta por página de ids, no una por id
    suspend fun <T : Any> byIds(
        objectName: String,
        type: Class<T>,
        ids: Collection<String>
    ): Map<String, T> =
        ids
            .distinct()
            .chunked(PageRequest.MAX_SIZE)
            .flatMap { chunk ->
                records.list(objectName, RecordQuery(page = PageRequest.of(0, chunk.size), ids = chunk.map(UUID::fromString))).content
            }.associate { it.id to read(type, it) }

    suspend fun <T : Any> create(
        objectName: String,
        type: Class<T>,
        attributes: Map<String, Any?>
    ): T = read(type, records.create(objectName, RecordRequest(escribibles(objectName, attributes))))

    // el update de core reemplaza cada campo que el usuario puede escribir: uno que falta en la petición se borra.
    // se manda lo que el dto sabe sobre lo guardado, así un campo agregado en el admin (y ausente del dto) no se borra.
    // un campo que los roles del usuario bloquean no se manda (core rechazaría todo) y core lo deja como está
    suspend fun <T : Any> replace(
        objectName: String,
        type: Class<T>,
        id: UUID,
        attributes: Map<String, Any?>
    ): T {
        val stored = records.get(objectName, id).attributes
        return read(type, records.update(objectName, id, RecordRequest(escribibles(objectName, stored + attributes))))
    }

    suspend fun delete(
        objectName: String,
        id: UUID
    ) = records.delete(objectName, id)

    suspend fun count(objectName: String): Long = records.list(objectName, RecordQuery(page = PageRequest.of(0, 1))).totalElements

    // el valor más alto que guarda un campo. los null quedan fuera: postgres los ordena primero al descender
    suspend fun highest(
        objectName: String,
        field: String
    ): String? {
        val criterion =
            RecordCriterion { definition, _ ->
                "${definition.fields.first { it.name == field }.columnName} IS NOT NULL"
            }
        return records
            .list(objectName, RecordQuery(page = PageRequest.of(0, 1), sort = field, descending = true, criteria = listOf(criterion)))
            .content
            .firstOrNull()
            ?.attributes
            ?.get(field)
            ?.toString()
    }

    private fun <T : Any> read(
        type: Class<T>,
        response: RecordResponse
    ): T = Records.read(type, response.id, response.attributes)

    private suspend fun escribibles(
        objectName: String,
        attributes: Map<String, Any?>
    ) = soloEscribibles(metadata.definitionOf(objectName), attributes)

    companion object {
        // los atributos que el usuario puede escribir, según el objeto como él lo ve (MetadataService.definitionOf):
        // un campo que sus roles bloquean llega bloqueado (editable = false, como uno que el admin hizo de solo
        // lectura), uno que no puede leer no llega (core nunca deja escribir lo que no se puede leer). una clave que
        // no es campo también sale: core la ignora
        fun soloEscribibles(
            definition: ObjectDefinition,
            attributes: Map<String, Any?>
        ): Map<String, Any?> {
            val escribibles = definition.fields.filter { it.editable }.mapTo(HashSet()) { it.name }
            return attributes.filterKeys { it in escribibles }
        }
    }
}
