package caja.comun

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRow
import wasichai.core.data.RecordStore
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.ObjectDefinition
import java.lang.reflect.Modifier
import java.util.UUID

// la guarda antes de escribir (caja-backend#20), sin Spring ni base: lo que deja llegar al almacén de wasichai y lo que
// no, con la marca EscrituraDeCaja y sin ella. un almacén de prueba anota cada llamada que le llega
class GuardiaDeEscriturasTest {
    private val llegadas = mutableListOf<String>()
    private val guardia = GuardiaDeEscrituras(AlmacenQueAnota(llegadas))

    @Test
    fun `sin la marca, ningun objeto de caja se escribe, y nada llega al almacen`() {
        GuardiaDeEscrituras.PROTEGIDOS.forEach { objeto ->
            val definicion = definicion(objeto)
            val alta = rechaza { guardia.insert(definicion, ORGANIZACION, USUARIO, mapOf("importe" to "1.00"), emptyMap()) }
            assertTrue(alta.message.contains("«$objeto»") && alta.message.contains("caja-backend#20"), alta.message)
            rechaza { guardia.update(definicion, ORGANIZACION, USUARIO, UUID.randomUUID(), emptyMap(), emptyMap()) }
            rechaza { guardia.transitionState(definicion, ORGANIZACION, USUARIO, UUID.randomUUID(), null, "OTRO") }
            rechaza { guardia.delete(definicion, ORGANIZACION, UUID.randomUUID()) }
        }
        assertEquals(emptyList<String>(), llegadas)
    }

    @Test
    fun `con la marca, caja da de alta todo lo suyo y cambia solo lo que no se agrega`() {
        runBlocking {
            withContext(EscrituraDeCaja) {
                GuardiaDeEscrituras.PROTEGIDOS.forEach { guardia.insert(definicion(it), ORGANIZACION, USUARIO, emptyMap(), emptyMap()) }
                listOf(PAGO_EVENTO, TURNO, ORDEN_DE_COBRO).forEach {
                    guardia.update(definicion(it), ORGANIZACION, USUARIO, UUID.randomUUID(), emptyMap(), emptyMap())
                    guardia.transitionState(definicion(it), ORGANIZACION, USUARIO, UUID.randomUUID(), null, "OTRO")
                }
            }
        }
        assertEquals(
            GuardiaDeEscrituras.PROTEGIDOS.map { "insert $it" } +
                listOf(PAGO_EVENTO, TURNO, ORDEN_DE_COBRO).flatMap { listOf("update $it", "transitionState $it") },
            llegadas
        )
    }

    @Test
    fun `lo que solo se agrega no se cambia ni con la marca, y nada de caja se borra nunca`() {
        GuardiaDeEscrituras.SOLO_SE_AGREGAN.forEach { objeto ->
            val cambio =
                rechaza {
                    withContext(
                        EscrituraDeCaja
                    ) { guardia.update(definicion(objeto), ORGANIZACION, USUARIO, UUID.randomUUID(), emptyMap(), emptyMap()) }
                }
            assertTrue(cambio.message.contains("solo se agrega"), cambio.message)
            rechaza { withContext(EscrituraDeCaja) { guardia.transitionState(definicion(objeto), ORGANIZACION, USUARIO, UUID.randomUUID(), null, "OTRO") } }
        }
        GuardiaDeEscrituras.PROTEGIDOS.forEach { objeto ->
            rechaza { withContext(EscrituraDeCaja) { guardia.delete(definicion(objeto), ORGANIZACION, UUID.randomUUID()) } }
        }
        assertEquals(emptyList<String>(), llegadas)
    }

    @Test
    fun `lo que no es de caja pasa sin la marca, y las lecturas tambien`() {
        runBlocking {
            listOf(AREA, CAJA, TASA).forEach {
                guardia.insert(definicion(it), ORGANIZACION, USUARIO, emptyMap(), emptyMap())
                guardia.update(definicion(it), ORGANIZACION, USUARIO, UUID.randomUUID(), emptyMap(), emptyMap())
                guardia.transitionState(definicion(it), ORGANIZACION, USUARIO, UUID.randomUUID(), null, "OTRO")
                guardia.delete(definicion(it), ORGANIZACION, UUID.randomUUID())
            }
            guardia.findById(definicion(RECIBO), ORGANIZACION, UUID.randomUUID())
            guardia.query(definicion(PAGO_EVENTO), ORGANIZACION, RecordQuery(PageRequest.of(0, 1)))
        }
        assertEquals(
            listOf(AREA, CAJA, TASA).flatMap { listOf("insert $it", "update $it", "transitionState $it", "delete $it") } +
                listOf("findById $RECIBO", "query $PAGO_EVENTO"),
            llegadas
        )
    }

    @Test
    fun `los objetos de caja son los diez del modelo que no son catalogo`() {
        assertEquals(
            setOf(RECIBO, LINEA_RECIBO, ANULACION_RECIBO, REIMPRESION_RECIBO, CIERRE_TURNO, CIERRE_TURNO_LINEA, REVERSION_CIERRE),
            GuardiaDeEscrituras.SOLO_SE_AGREGAN
        )
        assertEquals(GuardiaDeEscrituras.SOLO_SE_AGREGAN + setOf(PAGO_EVENTO, TURNO, ORDEN_DE_COBRO), GuardiaDeEscrituras.PROTEGIDOS)
    }

    // la guarda delega por `by`: un método de escritura que una versión nueva de wasichai agregue al RecordStore pasaría
    // sin mirar la marca. esto falla antes, y obliga a decidir qué hace la guarda con él
    @Test
    fun `el RecordStore de wasichai tiene los metodos que la guarda conoce`() {
        val metodos =
            RecordStore::class.java.declaredMethods
                .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
        assertEquals(setOf("insert", "update", "transitionState", "delete", "findById", "query"), metodos)
    }

    private fun rechaza(bloque: suspend () -> Unit): ForbiddenException = assertThrows(ForbiddenException::class.java) { runBlocking { bloque() } }

    private fun definicion(objeto: String) =
        ObjectDefinition(CustomObject(UUID.randomUUID(), ORGANIZACION, objeto, objeto, objeto, null, true, "t_$objeto", null, null), emptyList())

    // anota cada llamada como «método objeto», y contesta lo mínimo
    private class AlmacenQueAnota(
        private val llegadas: MutableList<String>
    ) : RecordStore {
        private fun fila(
            metodo: String,
            definition: ObjectDefinition
        ): RecordRow {
            llegadas += "$metodo ${definition.obj.name}"
            return RecordRow(UUID.randomUUID(), null, null, emptyMap())
        }

        override suspend fun insert(
            definition: ObjectDefinition,
            organizationId: UUID,
            userId: UUID,
            attributes: Map<String, Any?>,
            sections: Map<String, Map<String, Any?>>,
            workflow: ObjectWorkflowState
        ) = fila("insert", definition)

        override suspend fun update(
            definition: ObjectDefinition,
            organizationId: UUID,
            userId: UUID,
            id: UUID,
            attributes: Map<String, Any?>,
            sections: Map<String, Map<String, Any?>>,
            withState: Boolean
        ) = fila("update", definition)

        override suspend fun transitionState(
            definition: ObjectDefinition,
            organizationId: UUID,
            userId: UUID,
            id: UUID,
            from: String?,
            to: String
        ) = fila("transitionState", definition)

        override suspend fun delete(
            definition: ObjectDefinition,
            organizationId: UUID,
            id: UUID
        ): Boolean {
            fila("delete", definition)
            return true
        }

        override suspend fun findById(
            definition: ObjectDefinition,
            organizationId: UUID,
            id: UUID,
            createdBy: UUID?,
            withState: Boolean
        ) = fila("findById", definition)

        override suspend fun query(
            definition: ObjectDefinition,
            organizationId: UUID,
            query: RecordQuery
        ): PageResponse<RecordRow> {
            fila("query", definition)
            return PageResponse(emptyList(), 0, 1, 0, 0)
        }
    }

    private companion object {
        val ORGANIZACION: UUID = UUID.randomUUID()
        val USUARIO: UUID = UUID.randomUUID()
    }
}
