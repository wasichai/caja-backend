package caja.buzon

import caja.CajaApiTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import wasichai.core.platform.ClusterLock

// un solo publicador por base: mientras uno tiene el cerrojo del buzón, otro tomar() da null, y al soltarlo se puede
// tomar otra vez. es ClusterLock.tryLock, un pg_try_advisory_lock de sesión, cada intento sobre su propia conexión
class CerrojoBuzonTest : CajaApiTest() {
    @Autowired
    lateinit var cerrojo: CerrojoBuzon

    @Test
    fun `mientras uno tiene el cerrojo nadie mas lo toma, y al soltarlo si`() =
        runBlocking {
            val primero = tomarCuandoSeLibere()
            try {
                assertNull(cerrojo.tomar(), "con el cerrojo tomado, un segundo publicador no lo toma")
                assertNull(cerrojo.tomar(), "ni un tercero: no es reentrante entre conexiones")
            } finally {
                primero.release()
            }

            val otraVez = tomarCuandoSeLibere()
            assertNotNull(otraVez, "suelto, se toma otra vez")
            otraVez.release()
        }

    // el bucle de otra clase de la corrida podría tenerlo un instante: se reintenta hasta que se libere
    private suspend fun tomarCuandoSeLibere(): ClusterLock.Lease =
        withTimeout(30_000) {
            var tomado = cerrojo.tomar()
            while (tomado == null) {
                delay(50)
                tomado = cerrojo.tomar()
            }
            tomado
        }
}
