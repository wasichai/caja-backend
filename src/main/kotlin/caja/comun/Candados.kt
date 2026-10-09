package caja.comun

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import wasichai.core.platform.ClusterLock

// las clases de candado de caja. el orden en que se toman es el de la lista: turno-clave → turno → órdenes (por id) →
// serie → recibo → pago. el cobro toma los cuatro primeros; la anulación, el del turno del recibo y los de sus órdenes;
// la reimpresión, solo el del recibo; la explicación de un pago sin entregar y cada marca del publicador del buzón, solo
// el de su evento
enum class Candado { TURNO_CLAVE, TURNO, ORDEN, SERIE, RECIBO, PAGO }

// los candados consultivos de transacción de postgres, por clase y clave, sobre ClusterLock de wasichai
// (pg_advisory_xact_lock de ClusterLock.lockId). wasichai no tiene bloqueo de fila: lo que dos peticiones no pueden hacer
// a la vez se ordena aquí. el candado se une a la transacción en curso y se suelta en su commit o su rollback, nunca
// antes. fuera de una transacción falla: ClusterLock abriría una propia, el candado se soltaría al volver, y no
// protegería nada.
// la clave es «caja.<clase>.<clave>»: una clase no lleva puntos, así que dos claves de clases distintas nunca son el
// mismo texto, y lockId (los primeros 64 bits de su SHA-256) no las junta en la práctica: un choque entre una clave de
// turno y una de serie volvería el mismo candado a los dos, y dos cobros podrían tomarlo en órdenes distintos y
// esperarse en cruz (40P01). con hashtext, de 32 bits, eso lo evitaba la forma de dos enteros
@Component
class Candados(
    private val cerrojos: ClusterLock
) {
    suspend fun bloquear(
        candado: Candado,
        clave: String
    ) {
        check(enTransaccion()) { "El candado ${candado.name} '$clave' se toma dentro de una transacción: fuera de ella no protege nada" }
        cerrojos.withXactLock(clave(candado, clave)) {}
    }

    // la transacción reactiva vive en el contexto de reactor, que la corrutina lleva consigo
    private suspend fun enTransaccion(): Boolean =
        try {
            TransactionSynchronizationManager.forCurrentTransaction().awaitSingle().isActualTransactionActive
        } catch (_: NoTransactionException) {
            false
        }

    companion object {
        // la clave de ClusterLock de un candado: la misma para quien lo toma con bloquear y para quien lo toma con su
        // propia transacción (BuzonStore, al marcar un evento)
        fun clave(
            candado: Candado,
            clave: String
        ): String = "caja.${candado.name}.$clave"
    }
}
