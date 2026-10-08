package caja.comun

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import wasichai.core.common.ForbiddenException
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.data.RecordRow
import wasichai.core.data.RecordStore
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

// la guarda de la segunda puerta (caja-backend#20): el rodeo de wasichai#15 mientras wasichai no tenga una guarda antes
// de escribir. la API genérica de wasichai (POST/PUT/DELETE /api/objects/{objeto}/records) aplica los permisos de
// objeto de core y nada más, y caja escribe como el usuario que llama, así que quien cobra o anula tiene esos permisos.
// por esa puerta un SUPERVISOR_CAJA escribía un acta de anulación forjada (el arqueo restaba su importe: el dinero
// salía del cajón y el cierre cuadraba igual), o cambiaba el evento_id de un pago ENTREGADO y lo volvía a PENDIENTE,
// para que se reenviara con otro pagoId; un CAJERO le bajaba el importe a una orden antes de cobrarla, o escribía un
// recibo. ninguna regla de caja corría por ahí, y un detector que corre después de escribir solo podía anotarlo.
//
// esto envuelve el RecordStore de wasichai (AlmacenDeRegistros), por donde pasa TODA escritura de RecordService: la API
// genérica, el admin y la api de caja. RecordService ya comprobó los permisos (ADMIN se los salta) y llama al store en
// la corrutina de quien escribe, así que aquí se ve la marca EscrituraDeCaja que Registros pone alrededor de cada
// create y replace. un cliente http no puede ponerla. sobre los objetos de caja:
// - SIN LA MARCA NO SE ESCRIBE NADA: ni un alta, ni un cambio, ni un borrado, tampoco un ADMIN. 403 antes de tocar la
//   base: no queda fila, ni auditoría, ni listener al que avisar;
// - lo que SOLO SE AGREGA (el recibo, sus líneas, su acta, sus reimpresiones, el cierre, sus líneas y su reversión) no
//   se cambia ni con la marca: anular es agregar un acta y reversar es agregar una reversión
//   (InmutabilidadDelReciboTest lo vigila además en el código);
// - NADA SE BORRA, nunca: caja no borra.
// cada rechazo deja una línea WARN que empieza con ESCRITURA FUERA DE CAJA RECHAZADA, con el objeto, el id y el
// usuario: alguien con permiso lo intentó por la segunda puerta. lo que NO ve: quien escribe en la base directamente
// (así marca BuzonStore sus entregas, y es caja), el borrado del objeto entero por la api de metadatos y un módulo que
// escriba sin pasar por el RecordStore (wasichai-automation, que caja no instala)
class GuardiaDeEscrituras(
    private val almacen: RecordStore
) : RecordStore by almacen {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun insert(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        workflow: ObjectWorkflowState
    ): RecordRow {
        exigirLaMarca("CREATE", definition, organizationId, userId, null) { deLaOrden(definition, attributes) }
        return almacen.insert(definition, organizationId, userId, attributes, sections, workflow)
    }

    override suspend fun update(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        withState: Boolean
    ): RecordRow {
        exigirLaMarca("UPDATE", definition, organizationId, userId, id)
        noSeCambia("UPDATE", definition, organizationId, userId, id)
        return almacen.update(definition, organizationId, userId, id, attributes, sections, withState)
    }

    override suspend fun transitionState(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        from: String?,
        to: String
    ): RecordRow? {
        exigirLaMarca("TRANSITION", definition, organizationId, userId, id)
        noSeCambia("TRANSITION", definition, organizationId, userId, id)
        return almacen.transitionState(definition, organizationId, userId, id, from, to)
    }

    // la única puerta para borrar que hay en src/main, y es para cerrarla: un objeto de caja no se borra, ni con la
    // marca. lo demás (una caja, una tasa) sigue su camino
    override suspend fun delete(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID
    ): Boolean {
        if (protegido(definition)) {
            throw rechazo(
                "DELETE",
                definition,
                organizationId,
                null,
                id,
                "un objeto de caja no se borra, por ninguna puerta: un recibo se anula y un cierre se reversa"
            )
        }
        return almacen.delete(definition, organizationId, id)
    }

    // sin la marca, un objeto de caja no se escribe
    private suspend fun exigirLaMarca(
        operacion: String,
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID?,
        detalle: () -> String = { "" }
    ) {
        if (!protegido(definition) || currentCoroutineContext()[EscrituraDeCaja.Clave] != null) return
        throw rechazo(
            operacion,
            definition,
            organizationId,
            userId,
            id,
            "«${definition.obj.name}» solo lo escribe caja, por su api (/api/caja/...), que corre sus reglas. La API genérica no las corre, " +
                "y no lo escribe nadie por ella, tampoco un ADMIN (caja-backend#20)",
            detalle()
        )
    }

    // lo que solo se agrega no se cambia, ni siquiera desde caja
    private fun noSeCambia(
        operacion: String,
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID
    ) {
        if (definition.obj.name !in SOLO_SE_AGREGAN) return
        throw rechazo(
            operacion,
            definition,
            organizationId,
            userId,
            id,
            "«${definition.obj.name}» solo se agrega: no se cambia, por ninguna puerta. Un recibo se anula con su acta y un cierre se reversa"
        )
    }

    private fun rechazo(
        operacion: String,
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID?,
        id: UUID?,
        motivo: String,
        detalle: String = ""
    ): ForbiddenException {
        log.warn(
            "ESCRITURA FUERA DE CAJA RECHAZADA: {} {} {} por el usuario {} de la organización {}. {}{}",
            operacion,
            definition.obj.name,
            id ?: "(alta)",
            userId,
            organizationId,
            motivo,
            detalle
        )
        return ForbiddenException(motivo)
    }

    // de un alta de orden por fuera, su importe si el alta de caja lo habría rechazado: el que rompería un recibo. en
    // una sola línea, como todo lo que va al registro
    private fun deLaOrden(
        definition: ObjectDefinition,
        attributes: Map<String, Any?>
    ): String {
        if (definition.obj.name != ORDEN_DE_COBRO) return ""
        val importe = attributes["importe"]?.toString()?.toBigDecimalOrNull()
        return defectoDelImporte(importe)?.let { ". Tiene el importe roto (${importe?.toPlainString()}): $it" }.orEmpty()
    }

    private fun protegido(definition: ObjectDefinition): Boolean = definition.obj.name in PROTEGIDOS

    companion object {
        // lo que solo se agrega: anular y reversar son filas nuevas
        val SOLO_SE_AGREGAN =
            setOf(
                RECIBO,
                LINEA_RECIBO,
                ANULACION_RECIBO,
                REIMPRESION_RECIBO,
                CIERRE_TURNO,
                CIERRE_TURNO_LINEA,
                REVERSION_CIERRE
            )

        // todo lo que escribe caja: lo que solo se agrega, el buzón (que se explica), el turno (que se cierra y se
        // reabre) y la orden de cobro (que se cobra y vuelve a PENDIENTE al anular), cuya única alta es POST
        // /api/caja/ordenes-de-cobro
        val PROTEGIDOS = SOLO_SE_AGREGAN + setOf(PAGO_EVENTO, TURNO, ORDEN_DE_COBRO)
    }
}
