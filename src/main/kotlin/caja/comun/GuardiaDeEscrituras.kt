package caja.comun

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard

// la guarda de la segunda puerta (caja-backend#20), ahora un RecordWriteGuard de wasichai (antes un decorador del
// RecordStore, el rodeo de wasichai#15). la API genérica de wasichai (POST/PUT/DELETE /api/objects/{objeto}/records)
// aplica los permisos de objeto de core y nada más, y caja escribe como el usuario que llama, así que quien cobra o anula
// tiene esos permisos. por esa puerta un SUPERVISOR_CAJA escribía un acta de anulación forjada (el arqueo restaba su
// importe: el dinero salía del cajón y el cierre cuadraba igual), o cambiaba el evento_id de un pago ENTREGADO y lo
// volvía a PENDIENTE, para que se reenviara con otro pagoId; un CAJERO le bajaba el importe a una orden antes de cobrarla,
// o escribía un recibo. ninguna regla de caja corría por ahí.
//
// la primera línea de defensa es el modelo: los diez objetos de caja son apiOnly (la API genérica da 403, ADMIN
// incluido, desde wasichai, sin pasar por aquí) y ocho son appendOnly (ni caja los cambia: 409). esto cubre lo que el
// modelo no ve, lo que corre DENTRO del proceso: wasichai llama a beforeWrite antes de toda escritura de RecordService,
// en la corrutina de quien escribe, así que aquí se ve la marca EscrituraDeCaja que Registros pone alrededor de cada
// create y replace, y BuzonStore alrededor de cada marca del publicador. un cliente http no puede ponerla. sobre los
// objetos de caja:
// - SIN LA MARCA NO SE ESCRIBE NADA: ni un alta ni un cambio, tampoco la plataforma ni otro módulo. 403 antes de tocar la
//   base: no queda fila, ni auditoría, ni listener al que avisar;
// - NADA SE BORRA, nunca: caja no borra, ni con la marca.
// cada rechazo deja una línea WARN que empieza con ESCRITURA FUERA DE CAJA RECHAZADA, con el objeto, el id y quien
// escribe (el usuario, o la plataforma). lo que NO ve: quien escribe en la base directamente y el borrado del objeto
// entero por la api de metadatos
@Component
class GuardiaDeEscrituras : RecordWriteGuard {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName !in PROTEGIDOS) return
        if (change.kind == RecordChangeKind.DELETED) {
            throw rechazo(change, "un objeto de caja no se borra, por ninguna puerta: un recibo se anula y un cierre se reversa")
        }
        if (currentCoroutineContext()[EscrituraDeCaja.Clave] != null) return
        // a la API genérica ya la para apiOnly, antes de llegar aquí: lo que llega es una escritura dentro del proceso
        throw rechazo(
            change,
            "«${change.objectName}» solo lo escribe caja, por su api (/api/caja/...), que corre sus reglas. Una escritura dentro del " +
                "proceso sin la marca de caja (EscrituraDeCaja), de otro módulo o de la plataforma, no las corre y no se acepta (caja-backend#20)",
            deLaOrden(change)
        )
    }

    private fun rechazo(
        change: RecordWrite,
        motivo: String,
        detalle: String = ""
    ): ForbiddenException {
        log.warn(
            "ESCRITURA FUERA DE CAJA RECHAZADA: {} {} {} por {} de la organización {}. {}{}",
            operacion(change.kind),
            change.objectName,
            change.recordId ?: "(alta)",
            change.userId?.let { "el usuario $it" } ?: "la plataforma",
            change.organizationId,
            motivo,
            detalle
        )
        return ForbiddenException(motivo)
    }

    private fun operacion(kind: RecordChangeKind): String =
        when (kind) {
            RecordChangeKind.CREATED -> "CREATE"
            RecordChangeKind.UPDATED -> "UPDATE"
            RecordChangeKind.DELETED -> "DELETE"
            RecordChangeKind.TRANSITIONED -> "TRANSITION"
        }

    // de un alta de orden por fuera, su importe si el alta de caja lo habría rechazado: el que rompería un recibo. en
    // una sola línea, como todo lo que va al registro
    private fun deLaOrden(change: RecordWrite): String {
        if (change.objectName != ORDEN_DE_COBRO || change.kind != RecordChangeKind.CREATED) return ""
        val importe =
            change.attributes
                ?.get("importe")
                ?.toString()
                ?.toBigDecimalOrNull()
        return defectoDelImporte(importe)?.let { ". Tiene el importe roto (${importe?.toPlainString()}): $it" }.orEmpty()
    }

    companion object {
        // todo lo que escribe caja: los diez objetos que no son catálogo. los ocho que solo se agregan son appendOnly en
        // el modelo (anular es agregar un acta y reversar es agregar una reversión); el pago_evento (que se explica) y la
        // orden de cobro (que se cobra y vuelve a PENDIENTE al anular) se cambian, y su única alta es POST
        // /api/caja/ordenes-de-cobro
        val PROTEGIDOS =
            setOf(
                RECIBO,
                LINEA_RECIBO,
                ANULACION_RECIBO,
                REIMPRESION_RECIBO,
                CIERRE_TURNO,
                CIERRE_TURNO_LINEA,
                REVERSION_CIERRE,
                TURNO,
                PAGO_EVENTO,
                ORDEN_DE_COBRO
            )
    }
}
