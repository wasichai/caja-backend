package caja.buzon

import caja.CajaApiTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

// el buzón viene apagado (caja.buzon.habilitado=false, decisión 5): ningún bucle arranca, y la app arranca sin
// responsable de la conciliación. es el contexto de todas las demás pruebas de integración, que no lo nombran
class BuzonApagadoApiTest : CajaApiTest() {
    @Autowired
    lateinit var bucle: BucleDelBuzon

    @Autowired
    lateinit var propiedades: PropiedadesDelBuzon

    @Test
    fun `con el buzon deshabilitado no arranca ningun bucle`() {
        assertFalse(propiedades.habilitado)
        assertFalse(bucle.isRunning)
    }
}
