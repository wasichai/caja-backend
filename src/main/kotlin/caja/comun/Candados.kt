package caja.comun

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager

// los candados consultivos de transacción de postgres, por clave (como DocumentRepository.lockRecordType de wasichai).
// wasichai no tiene bloqueo de fila ni unicidad compuesta: lo que dos peticiones no pueden hacer a la vez se ordena
// aquí. el candado lo tiene la transacción en curso y se suelta en su commit o su rollback, nunca antes. fuera de una
// transacción falla: en autocommit se tomaría y se soltaría en la misma sentencia, y no protegería nada.
// hashtext da un int4: dos claves distintas pueden caer en el mismo candado. eso solo ordena de más, nunca de menos
@Component
class Candados(
    private val db: DatabaseClient
) {
    suspend fun bloquear(clave: String) {
        check(enTransaccion()) { "El candado '$clave' se toma dentro de una transacción: fuera de ella no protege nada" }
        db
            .sql("SELECT pg_advisory_xact_lock(hashtext(:clave))")
            .bind("clave", clave)
            .fetch()
            .one()
            .awaitSingle()
    }

    // la transacción reactiva vive en el contexto de reactor, que la corrutina lleva consigo
    private suspend fun enTransaccion(): Boolean =
        try {
            TransactionSynchronizationManager.forCurrentTransaction().awaitSingle().isActualTransactionActive
        } catch (_: NoTransactionException) {
            false
        }
}
