package caja.comun

import org.springframework.stereotype.Component
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import java.util.UUID

// las listas que cuelgan de un registro (las líneas de un recibo...): se listan bajo el padre, se agregan bajo él y
// se cambian y borran por su id. el padre no se mueve: un cuerpo no puede cambiarlo
@Component
class Listas(
    private val registros: Registros
) {
    suspend fun <T : Any> listar(
        objectName: String,
        type: Class<T>,
        parentField: String,
        parent: UUID
    ): List<T> = registros.all(objectName, type, filters = mapOf(parentField to parent.toString()), sort = "created_at")

    suspend fun <T : Any> agregar(
        objectName: String,
        type: Class<T>,
        parentField: String,
        parent: UUID,
        body: T
    ): T = registros.create(objectName, type, Records.attributes(body) + (parentField to parent.toString()))

    suspend fun <T : Any> cambiar(
        objectName: String,
        type: Class<T>,
        parentField: String,
        id: UUID,
        body: T
    ): T {
        val parent =
            registros.get(objectName, Map::class.java, id)[parentField]
                ?: throw NotFoundException("$objectName $id has no $parentField")
        return registros.replace(objectName, type, id, Records.attributes(body) + (parentField to parent))
    }

    suspend fun borrar(
        objectName: String,
        id: UUID
    ) = registros.delete(objectName, id)

    // cuántas filas nombran al padre: una consulta, sean las que sean
    suspend fun contar(
        objectName: String,
        parentField: String,
        parent: UUID
    ): Long =
        registros
            .page(objectName, Map::class.java, RecordQuery(page = PageRequest.of(0, 1), filters = mapOf(parentField to parent.toString())))
            .totalElements
}
