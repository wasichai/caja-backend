package caja.comun

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener

// el detector de la segunda puerta (wasichai#15; hallazgo de la revisión del PR 4b). la API genérica de wasichai
// (POST/PUT/DELETE /api/objects/{objeto}/records) aplica los permisos de objeto de core y nada más: un cajero, que tiene
// CREATE sobre pago_evento para cobrar, puede escribir por ella un pago_evento inventado, o un cierre_turno que cierre
// el turno de otro. ninguna regla de caja corre por esa puerta.
//
// esto escribe una línea ERROR por cada creación, cambio o borrado de un objeto protegido que NO lleva la marca
// EscrituraDeCaja, es decir, que no pasó por la api de caja: el recibo y sus líneas, el buzón, la anulación y la
// reimpresión, el turno, su cierre con sus líneas y su reversión, y los cambios de una orden de cobro (darla de alta
// por la API genérica es lo que hace un sistema de origen; cambiarla es marcarla PAGADA sin recibo, o devolverla a
// PENDIENTE). NO PUEDE VETAR: wasichai llama a los listeners después de escribir (ADR-0025), y lanzar aquí solo
// convertiría en un 500 una escritura que ya ocurrió. la línea es para que se vea; impedirla es wasichai#15
@Component
class GuardiaDeEscrituras : RecordChangeListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun recordChanged(change: RecordChange) {
        if (currentCoroutineContext()[EscrituraDeCaja.Clave] != null) return
        if (!vigilado(change)) return
        log.error(
            "ESCRITURA FUERA DE CAJA: {} {} {} por el usuario {} de la organización {}, sin pasar por /api/caja (la API genérica de " +
                "wasichai, wasichai#15). Ninguna regla de caja la comprobó: revísela",
            change.kind,
            change.objectName,
            change.recordId,
            change.userId,
            change.organizationId
        )
    }

    private fun vigilado(change: RecordChange): Boolean =
        change.objectName in PROTEGIDOS || (change.objectName == ORDEN_DE_COBRO && change.kind != RecordChangeKind.CREATED)

    companion object {
        val PROTEGIDOS =
            setOf(
                RECIBO,
                LINEA_RECIBO,
                PAGO_EVENTO,
                ANULACION_RECIBO,
                REIMPRESION_RECIBO,
                TURNO,
                CIERRE_TURNO,
                CIERRE_TURNO_LINEA,
                REVERSION_CIERRE
            )
    }
}
