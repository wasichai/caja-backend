package caja.comun

import caja.CajaApiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated

// la lectura de una sola foto (Transaccion.lectura): lo que otra transacción confirma entre dos lecturas no se ve
// dentro. es lo que deja cuadrar la página del listado de recibos con su desempate aunque un cobro se confirme a la
// vez (la carrera de la revisión de #14: la página 0, un recibo nuevo entre la página y la cuenta, drop(-1) y un 500)
class TransaccionTest : CajaApiTest() {
    @Autowired
    lateinit var transaccion: Transaccion

    @Autowired
    lateinit var db: DatabaseClient

    @BeforeEach
    fun tabla() =
        runBlocking<Unit> {
            db
                .sql("CREATE TABLE IF NOT EXISTS caja_prueba_lectura (clave text NOT NULL)")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    @Test
    fun `dentro de una lectura, lo que otro confirma entre dos lecturas no se ve`() =
        runBlocking {
            val clave = "lectura:${unico()}"
            insertar(clave)

            // otra transacción, en otra conexión, inserta y confirma entre las dos lecturas. se lanza fuera de la
            // lectura: lanzada dentro, heredaría su transacción
            val primeraHecha = CompletableDeferred<Unit>()
            val confirmada = CompletableDeferred<Unit>()
            val escritor =
                async {
                    primeraHecha.await()
                    transaccion.en { insertar(clave) }
                    confirmada.complete(Unit)
                }
            val (antes, despues) =
                transaccion.lectura {
                    val antes = contar(clave)
                    primeraHecha.complete(Unit)
                    confirmada.await()
                    antes to contar(clave)
                }
            escritor.await()

            assertEquals(1L, antes)
            assertEquals(antes, despues, "la segunda lectura ve la misma foto que la primera")
            assertEquals(2L, contar(clave), "fuera de la lectura, lo confirmado se ve")
        }

    @Test
    fun `una lectura no escribe`() {
        assertThrows<Exception> { runBlocking { transaccion.lectura { insertar("lectura:${unico()}") } } }
    }

    private suspend fun insertar(clave: String) {
        db
            .sql("INSERT INTO caja_prueba_lectura (clave) VALUES (:clave)")
            .bind("clave", clave)
            .fetch()
            .awaitRowsUpdated()
    }

    private suspend fun contar(clave: String): Long =
        db
            .sql("SELECT count(*) AS n FROM caja_prueba_lectura WHERE clave = :clave")
            .bind("clave", clave)
            .map { fila, _ -> fila.get("n", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()
}
