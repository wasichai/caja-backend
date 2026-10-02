package caja.comun

import caja.cobro.defectoDelImporte
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
// reimpresión, el turno, su cierre con sus líneas y su reversión, y la orden de cobro: su alta (la puerta de un sistema
// de origen es POST /api/caja/ordenes-de-cobro; por la API genérica no corre NINGUNA regla del alta: el importe, el
// sistema, la clave de origen, nacer PENDIENTE), y sus cambios (marcarla PAGADA sin recibo, devolverla a PENDIENTE,
// bajarle el importe). se anota TODA alta por fuera, no solo la que hoy rompe una regla: comprobarlas aquí sería
// copiar el alta en un listener, y una orden que las pasa sigue sin haber pasado por ella. si su importe es uno que el
// alta rechazaría (el que rompe un recibo), la línea lo dice. NO PUEDE VETAR: wasichai llama a los listeners después
// de escribir (ADR-0025), y lanzar aquí solo convertiría en un 500 una escritura que ya ocurrió. la línea es para que
// se vea; impedirla es wasichai#15
@Component
class GuardiaDeEscrituras : RecordChangeListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun recordChanged(change: RecordChange) {
        if (currentCoroutineContext()[EscrituraDeCaja.Clave] != null) return
        if (!vigilado(change)) return
        log.error(
            "ESCRITURA FUERA DE CAJA: {} {} {} por el usuario {} de la organización {}, sin pasar por /api/caja (la API genérica de " +
                "wasichai, wasichai#15). Ninguna regla de caja la comprobó: revísela{}",
            change.kind,
            change.objectName,
            change.recordId,
            change.userId,
            change.organizationId,
            deLaOrden(change)
        )
    }

    private fun vigilado(change: RecordChange): Boolean = change.objectName in PROTEGIDOS || change.objectName == ORDEN_DE_COBRO

    // lo que se sabe de una orden escrita por fuera: un alta que no pasó por el alta de caja, y su importe si el alta lo
    // habría rechazado (un borrado no tiene después que mirar). el importe es un DECIMAL de la base: una sola línea
    private fun deLaOrden(change: RecordChange): String {
        if (change.objectName != ORDEN_DE_COBRO) return ""
        val alta = if (change.kind == RecordChangeKind.CREATED) ". Es un alta que no pasó por el alta de caja (POST /api/caja/ordenes-de-cobro)" else ""
        val despues = change.after ?: return alta
        val importe = despues["importe"]?.toString()?.toBigDecimalOrNull()
        val roto =
            defectoDelImporte(importe)?.let {
                ". Tiene el importe roto (${importe?.toPlainString()}): $it, y cobrarla daría un recibo que no se puede contar"
            }
        return alta + roto.orEmpty()
    }

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
