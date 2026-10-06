package caja.comun

import caja.CajaApiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.ClusterLock

// el candado consultivo de transacción, sobre ClusterLock: fuera de una transacción falla (no protegería nada), dentro
// dura hasta que ella termina, y la misma clave en otra clase de candado es otro candado
class CandadosTest : CajaApiTest() {
    @Autowired
    lateinit var candados: Candados

    @Autowired
    lateinit var transaccion: Transaccion

    @Autowired
    lateinit var db: DatabaseClient

    @Test
    fun `fuera de una transaccion el candado falla`() {
        val error = assertThrows<IllegalStateException> { runBlocking { candados.bloquear(Candado.ORDEN, "prueba:${unico()}") } }
        assertEquals(true, error.message?.contains("transacción"), error.message)
    }

    @Test
    fun `dentro de una transaccion el candado dura hasta que ella termina`() =
        runBlocking {
            val clave = "prueba:${unico()}"
            val tomado = CompletableDeferred<Unit>()
            val soltar = CompletableDeferred<Unit>()
            val duenio =
                async {
                    transaccion.en {
                        candados.bloquear(Candado.TURNO, clave)
                        tomado.complete(Unit)
                        soltar.await()
                    }
                }
            tomado.await()
            assertEquals(false, otroLoToma(Candado.TURNO, clave), "mientras la transacción vive, nadie más lo toma")
            assertEquals(true, otroLoToma(Candado.SERIE, clave), "la misma clave en otra clase es otro candado")
            soltar.complete(Unit)
            duenio.await()
            assertEquals(true, otroLoToma(Candado.TURNO, clave), "al terminar, se suelta")
        }

    // si otra transacción lo toma sin esperar, con la clave que le da ClusterLock
    private suspend fun otroLoToma(
        candado: Candado,
        clave: String
    ): Boolean =
        transaccion.en {
            db
                .sql("SELECT pg_try_advisory_xact_lock(:id) AS tomado")
                .bind("id", ClusterLock.lockId(Candados.clave(candado, clave)))
                .map { fila, _ -> fila.get("tomado", java.lang.Boolean::class.java)!!.booleanValue() }
                .one()
                .awaitSingle()
        }
}
