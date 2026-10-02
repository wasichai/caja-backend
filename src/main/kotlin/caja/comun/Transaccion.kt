package caja.comun

import org.springframework.stereotype.Component
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.transaction.support.DefaultTransactionDefinition

// una transacción de la base alrededor de un bloque. RecordService de wasichai no abre ninguna (ADR-0025 de wasichai),
// pero escribe por DatabaseClient, que se une a la que abre esto: lo que el bloque escribe se confirma junto o se
// revierte junto. el usuario que llama sigue en el contexto: RecordService aplica sus permisos dentro igual que fuera.
// un error dentro revierte y se relanza tal cual; un unique que salta aborta la transacción entera, así que no se
// relee nada dentro de un catch (postgres contesta 25P02 a todo lo que siga)
//
// lectura: una transacción de solo lectura en REPEATABLE READ, para las consultas que hacen varias lecturas que tienen
// que cuadrar entre sí (el listado de recibos y su desempate): todas ven la misma foto de la base, la de su primera
// sentencia, aunque otro cobro se confirme entre una y otra
@Component
class Transaccion(
    private val operador: TransactionalOperator,
    gestor: ReactiveTransactionManager
) {
    private val lector =
        TransactionalOperator.create(
            gestor,
            DefaultTransactionDefinition().apply {
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                isReadOnly = true
            }
        )

    suspend fun <T> en(bloque: suspend () -> T): T = operador.executeAndAwait { bloque() }

    suspend fun <T> lectura(bloque: suspend () -> T): T = lector.executeAndAwait { bloque() }
}
