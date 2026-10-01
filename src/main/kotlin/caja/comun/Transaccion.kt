package caja.comun

import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

// una transacción de la base alrededor de un bloque. RecordService de wasichai no abre ninguna (ADR-0025 de wasichai),
// pero escribe por DatabaseClient, que se une a la que abre esto: lo que el bloque escribe se confirma junto o se
// revierte junto. el usuario que llama sigue en el contexto: RecordService aplica sus permisos dentro igual que fuera.
// un error dentro revierte y se relanza tal cual; un unique que salta aborta la transacción entera, así que no se
// relee nada dentro de un catch (postgres contesta 25P02 a todo lo que siga)
@Component
class Transaccion(
    private val operador: TransactionalOperator
) {
    suspend fun <T> en(bloque: suspend () -> T): T = operador.executeAndAwait { bloque() }
}
