package caja.buzon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

// el responsable de la conciliación (ResponsableDeLaConciliacion de caja): un pago que muere avisa a una persona con
// nombre y por un canal. con el buzón apagado no hace falta, y la app arranca sin él; con el buzón encendido es
// obligatorio, y sin él el arranque falla nombrando lo que falta
class ResponsableDeLaConciliacionTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(ConfiguracionDelBuzon::class.java, ResponsableDeLaConciliacion::class.java)

    @Test
    fun `con el buzon apagado arranca sin responsable`() {
        runner.run { contexto ->
            assertNull(contexto.startupFailure)
            assertFalse(contexto.getBean(PropiedadesDelBuzon::class.java).habilitado)
        }
    }

    @Test
    fun `con el buzon encendido y sin responsable ni canal, el arranque falla nombrandolos`() {
        runner.withPropertyValues("caja.buzon.habilitado=true").run { contexto ->
            val fallo = contexto.startupFailure
            assertNotNull(fallo)
            val mensaje = generateSequence(fallo) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            assertTrue(mensaje.contains("caja.conciliacion.responsable"), mensaje)
            assertTrue(mensaje.contains("caja.conciliacion.canal"), mensaje)
        }
    }

    @Test
    fun `con el buzon encendido, un responsable sin canal tampoco arranca`() {
        runner.withPropertyValues("caja.buzon.habilitado=true", "caja.conciliacion.responsable=Ana Quispe").run { contexto ->
            val mensaje = generateSequence(contexto.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            assertTrue(mensaje.contains("caja.conciliacion.canal"), mensaje)
        }
    }

    @Test
    fun `con el buzon encendido y el responsable, arranca y la configuracion trae sus valores por defecto`() {
        runner
            .withPropertyValues(
                "caja.buzon.habilitado=true",
                "caja.conciliacion.responsable= Ana Quispe ",
                "caja.conciliacion.canal=ana@muni.gob.pe",
                "caja.buzon.destinos.rentas.url=http://rentas:8080",
                "caja.buzon.destinos.rentas.token=secreto"
            ).run { contexto ->
                assertNull(contexto.startupFailure)
                assertEquals("Ana Quispe <ana@muni.gob.pe>", contexto.getBean(ResponsableDeLaConciliacion::class.java).toString())
                val propiedades = contexto.getBean(PropiedadesDelBuzon::class.java)
                assertEquals(Duration.ofSeconds(10), propiedades.intervalo)
                assertEquals(50, propiedades.porVuelta)
                assertEquals(8, propiedades.intentos)
                assertEquals(Duration.ofSeconds(10), propiedades.timeout)
                assertEquals("http://rentas:8080", propiedades.destinos.getValue("rentas").url)
                assertEquals("secreto", propiedades.destinos.getValue("rentas").token)
            }
    }
}
