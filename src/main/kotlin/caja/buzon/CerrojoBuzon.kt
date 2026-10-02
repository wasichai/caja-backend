package caja.buzon

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

// UN SOLO PUBLICADOR POR BASE, no uno por réplica (CerrojoEmision de srtm; wasichai no tiene programación de tareas ni
// ayuda de candados, wasichai#18). en cada vuelta el publicador intenta este candado consultivo de SESIÓN de postgres,
// pg_try_advisory_lock, sobre una conexión propia que retiene mientras lo tiene: si otra réplica lo tiene, esa vuelta no
// hace nada. una instancia que muere no se lo queda: postgres lo suelta al cerrarse su sesión. la conexión es del pool
// y vuelve a él sin el candado. no es pg_advisory_xact_lock (Candados): ese vive en una transacción, y la vuelta no
// abre ninguna mientras habla con el destino
@Component
class CerrojoBuzon(
    private val conexiones: ConnectionFactory
) {
    // null: otro publicador lo tiene
    suspend fun tomar(): Tomado? {
        val conexion = conexiones.create().awaitSingle()
        val tomado =
            try {
                consultar(conexion, "SELECT pg_try_advisory_lock(\$1)")
            } catch (e: Throwable) {
                cerrar(conexion)
                throw e
            }
        if (!tomado) {
            cerrar(conexion)
            return null
        }
        return Tomado {
            withContext(NonCancellable) {
                try {
                    consultar(conexion, "SELECT pg_advisory_unlock(\$1)")
                } finally {
                    cerrar(conexion)
                }
            }
        }
    }

    fun interface Tomado {
        suspend fun soltar()
    }

    private suspend fun consultar(
        conexion: Connection,
        sql: String
    ): Boolean =
        conexion
            .createStatement(sql)
            .bind("\$1", CLAVE)
            .execute()
            .awaitSingle()
            .map { row, _ -> row.get(0, Boolean::class.javaObjectType) }
            .awaitSingle() == true

    private suspend fun cerrar(conexion: Connection) {
        conexion.close().awaitFirstOrNull()
    }

    private companion object {
        // "CAJABUZN": la clave del publicador entre los candados consultivos de la base (la forma de un bigint: no se cruza
        // con los de Candados, que usan la de dos enteros)
        const val CLAVE = 0x43414A4142555A4EL
    }
}
