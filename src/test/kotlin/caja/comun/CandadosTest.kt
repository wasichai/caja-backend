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
// ella termina, y cada clase de candado tiene su propio espacio de claves
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

    @Test
    fun `cada clase de candado tiene su numero, distinto de las demas`() {
        assertEquals(
            Candado.entries.size,
            Candado.entries
                .map { it.clase }
                .toSet()
                .size
        )
    }

    // si otra transacción lo toma sin esperar
    private suspend fun otroLoToma(
        candado: Candado,
        clave: String
    ): Boolean =
        transaccion.en {
            db
                .sql("SELECT pg_try_advisory_xact_lock(:clase, hashtext(:clave)) AS tomado")
                .bind("clase", candado.clase)
                .bind("clave", clave)
                .map { fila, _ -> fila.get("tomado", java.lang.Boolean::class.java)!!.booleanValue() }
                .one()
                .awaitSingle()
        }
}
