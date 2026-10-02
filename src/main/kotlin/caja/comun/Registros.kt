package caja.comun

import kotlinx.coroutines.withContext
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
import wasichai.core.platform.SqlIdentifier
import java.util.UUID

// el RecordService de wasichai con los dtos de caja encima (portado de srtm-backend). RecordService comprueba los
// permisos de objeto y de campo del usuario que llama y valida cada escritura, así que aquí no se repite. no abre
// transacción: cada escritura se confirma sola, y un unique que salta llega como DuplicateKeyException. dentro de
// Transaccion.en se une a la transacción en curso y se confirma con ella (la cobranza). un dto lleva
// todos sus campos, y core rechaza la escritura entera que nombra un campo que el usuario no puede escribir: las
// escrituras mandan solo los escribibles. cada escritura lleva la marca EscrituraDeCaja: es la api de caja, y
// GuardiaDeEscrituras no la anota. NO HAY delete: caja no borra nada (un recibo se anula, un cierre se reversa), y una
// puerta para borrar que nadie llama es la que alguien usa mañana (InmutabilidadDelReciboTest)
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

    // cada registro que coincide, página por página (los de un recibo, los de un turno, los de un rango de días).
    //
    // LAS PÁGINAS VAN POR id, nunca por la clave pedida. core ordena por una sola columna (created_at por defecto) y
    // pagina con LIMIT/OFFSET; sobre una columna con empates, postgres no garantiza el mismo orden de los empatados de
    // una consulta a otra, así que una fila puede salir en dos páginas y otra en ninguna. y los empates son lo normal:
    // todo lo que escribe una transacción de caja lleva el mismo created_at (las líneas de un recibo). una suma hecha
    // sobre esas páginas contaba unas líneas dos veces y otras ninguna (RecaudacionApiTest). el id es único: cada fila
    // sale una vez. si se pide un orden, se aplica después, sobre todo lo leído, con el id como desempate
    suspend fun <T : Any> all(
        objectName: String,
        type: Class<T>,
        filters: Map<String, String> = emptyMap(),
        sort: String? = null,
        descending: Boolean = false,
        criteria: List<RecordCriterion> = emptyList()
    ): List<T> {
        val rows = mutableListOf<RecordResponse>()
        var page = 0
        do {
            val result =
                records.list(
                    objectName,
                    RecordQuery(
                        page = PageRequest.of(page, PageRequest.MAX_SIZE),
                        sort = POR_ID,
                        filters = filters,
                        criteria = criteria
                    )
                )
            rows += result.content
            page++
        } while (page < result.totalPages)
        return enOrden(rows, sort, descending).map { read(type, it) }
    }

    // el primero que cumple los filtros en ese orden, o null
    suspend fun <T : Any> primero(
        objectName: String,
        type: Class<T>,
        filters: Map<String, String>,
        sort: String? = null,
        descending: Boolean = false
    ): T? =
        records
            .list(objectName, RecordQuery(page = PageRequest.of(0, 1), sort = sort, descending = descending, filters = filters))
            .content
            .firstOrNull()
            ?.let { read(type, it) }

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

    // los registros cuya relación `field` nombra alguno de esos ids (las anulaciones de una página de recibos...): una
    // consulta por página de resultados, no una por id
    suspend fun <T : Any> byRelation(
        objectName: String,
        type: Class<T>,
        field: String,
        ids: Collection<String>
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val uuids = ids.distinct().map(UUID::fromString).toTypedArray()
        val criterion =
            RecordCriterion { definition, bind ->
                "${SqlIdentifier.quote(definition.fields.first { it.name == field }.columnName)} = ANY(${bind(uuids)})"
            }
        return all(objectName, type, criteria = listOf(criterion))
    }

    // cuántos registros cumplen los filtros y las condiciones
    suspend fun count(
        objectName: String,
        filters: Map<String, String> = emptyMap(),
        criteria: List<RecordCriterion> = emptyList()
    ): Long = records.list(objectName, RecordQuery(page = PageRequest.of(0, 1), filters = filters, criteria = criteria)).totalElements

    suspend fun <T : Any> create(
        objectName: String,
        type: Class<T>,
        attributes: Map<String, Any?>
    ): T = withContext(EscrituraDeCaja) { read(type, records.create(objectName, RecordRequest(escribibles(objectName, attributes)))) }

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
        return withContext(EscrituraDeCaja) { read(type, records.update(objectName, id, RecordRequest(escribibles(objectName, stored + attributes)))) }
    }

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
    ): T = Records.read(type, response.id, response.attributes + creadoEn(response))

    private suspend fun escribibles(
        objectName: String,
        attributes: Map<String, Any?>
    ) = soloEscribibles(metadata.definitionOf(objectName), attributes)

    companion object {
        // la única columna única por la que core sabe ordenar
        private const val POR_ID = "id"

        // lo leído en el orden pedido, como lo haría postgres (ascendente con los null al final; descendente al revés),
        // y los empatados por id: dos lecturas iguales salen iguales. sin orden pedido, el de las páginas (por id)
        internal fun enOrden(
            rows: List<RecordResponse>,
            sort: String?,
            descending: Boolean
        ): List<RecordResponse> {
            val clave = sort?.trim()?.lowercase()?.ifEmpty { null } ?: return rows
            val ascendente =
                Comparator<RecordResponse> { a, b ->
                    val x = valorDe(a, clave)
                    val y = valorDe(b, clave)
                    when {
                        x == null && y == null -> 0
                        x == null -> 1
                        y == null -> -1
                        else ->
                            @Suppress("UNCHECKED_CAST")
                            (x as Comparable<Any>).compareTo(y)
                    }
                }.thenBy { it.id }
            return rows.sortedWith(if (descending) ascendente.reversed() else ascendente)
        }

        private fun valorDe(
            row: RecordResponse,
            clave: String
        ): Any? =
            when (clave) {
                "id" -> row.id
                "created_at" -> row.createdAt
                "updated_at" -> row.updatedAt
                else -> row.attributes[clave]
            }

        // el instante en que core creó el registro, como created_at: un dto que lo quiere (el pago_evento, cuya hora es la
        // del tránsito) lo declara; los demás lo ignoran. un campo del modelo con ese nombre ganaría
        private fun creadoEn(response: RecordResponse): Map<String, Any?> =
            if ("created_at" in response.attributes) emptyMap() else mapOf("created_at" to response.createdAt?.toString())

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
