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

// el candado consultivo de transacción: fuera de una transacción falla (no protegería nada), dentro dura hasta que
// ella termina
class CandadosTest : CajaApiTest() {
    @Autowired
    lateinit var candados: Candados

    @Autowired
    lateinit var transaccion: Transaccion

    @Autowired
    lateinit var db: DatabaseClient

    @Test
    fun `fuera de una transaccion el candado falla`() {
        val error = assertThrows<IllegalStateException> { runBlocking { candados.bloquear("prueba:${unico()}") } }
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
                        candados.bloquear(clave)
                        tomado.complete(Unit)
                        soltar.await()
                    }
                }
            tomado.await()
            assertEquals(false, otroLoToma(clave), "mientras la transacción vive, nadie más lo toma")
            soltar.complete(Unit)
            duenio.await()
            assertEquals(true, otroLoToma(clave), "al terminar, se suelta")
        }

    // si otra transacción lo toma sin esperar
    private suspend fun otroLoToma(clave: String): Boolean =
        transaccion.en {
            db
                .sql("SELECT pg_try_advisory_xact_lock(hashtext(:clave)) AS tomado")
                .bind("clave", clave)
                .map { fila, _ -> fila.get("tomado", java.lang.Boolean::class.java)!!.booleanValue() }
                .one()
                .awaitSingle()
        }
}
