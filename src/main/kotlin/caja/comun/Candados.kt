package caja.comun

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager

// las clases de candado de caja, cada una con su número fijo: la forma de dos enteros de postgres,
// pg_advisory_xact_lock(<clase>, hashtext(<clave>)), separa sus espacios de claves. con un solo entero, un choque de
// hashtext entre una clave de turno y una de serie las volvería el mismo candado, y dos cobros podrían tomarlo en
// órdenes distintos y esperarse en cruz (40P01). los números llevan «CA» (0x4341) en los 16 bits altos: no se
// confunden con los de otro en la misma base. el orden en que se toman es el de la lista: turno-clave → turno →
// órdenes (por id) → serie → recibo. el cobro toma los cuatro primeros; la anulación, el del turno del recibo y los de
// sus órdenes; la reimpresión, solo el del recibo
enum class Candado(
    val clase: Int
) {
    TURNO_CLAVE(0x4341_0001),
    TURNO(0x4341_0002),
    ORDEN(0x4341_0003),
    SERIE(0x4341_0004),
    RECIBO(0x4341_0005)
}

// los candados consultivos de transacción de postgres, por clase y clave (DocumentRepository.lockRecordType de
// wasichai usa la forma de un entero, que es otro espacio). wasichai no tiene bloqueo de fila ni unicidad compuesta:
// lo que dos peticiones no pueden hacer a la vez se ordena aquí. el candado lo tiene la transacción en curso y se
// suelta en su commit o su rollback, nunca antes. fuera de una transacción falla: en autocommit se tomaría y se
// soltaría en la misma sentencia, y no protegería nada.
// hashtext da un int4: dos claves distintas de la MISMA clase pueden caer en el mismo candado. eso solo ordena de más
// dentro de esa clase, y no cruza el orden entre clases
@Component
class Candados(
    private val db: DatabaseClient
) {
    suspend fun bloquear(
        candado: Candado,
        clave: String
    ) {
        check(enTransaccion()) { "El candado ${candado.name} '$clave' se toma dentro de una transacción: fuera de ella no protege nada" }
        db
            .sql("SELECT pg_advisory_xact_lock(:clase, hashtext(:clave))")
            .bind("clase", candado.clase)
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
