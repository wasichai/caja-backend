package caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// los paquetes de caja dependen unos de otros en una sola dirección. comun (lo técnico y las reglas de una petición) y
// modelo (los registros y los enumerados de model/model.json) no importan a nadie de caja, y entre los de negocio no hay
// ciclos. con ciclos ningún paquete se entiende, se prueba ni se mueve sin los otros: cobro guardaba los registros de
// todos, y comun, cobro, turno, recibo y emision se importaban en círculo. la prueba lee los imports de src/main
class DependenciasEntrePaquetesTest {
    private val fuentes = File("src/main/kotlin/caja")

    @Test
    fun `comun y modelo no importan ningun otro paquete de caja`() {
        val dependencias = dependencias()
        assertTrue(dependencias.keys.containsAll(listOf("comun", "modelo", "cobro", "turno")), dependencias.keys.toString())

        assertEquals(emptySet<String>(), dependencias.getValue("comun"), "comun")
        assertEquals(emptySet<String>(), dependencias.getValue("modelo"), "modelo")
    }

    @Test
    fun `entre los paquetes de caja no hay ciclos`() {
        assertEquals(emptyList<List<String>>(), ciclos(dependencias()))
    }

    @Test
    fun `el vigia reconoce un ciclo y deja pasar lo que va en una sola direccion`() {
        assertEquals(listOf(listOf("a", "b")), ciclos(mapOf("a" to setOf("b"), "b" to setOf("a"), "c" to setOf("a"))))
        assertEquals(emptyList<List<String>>(), ciclos(mapOf("a" to setOf("b"), "b" to emptySet<String>(), "c" to setOf("a", "b"))))
    }

    // cada paquete de caja con los otros paquetes de caja que importa
    private fun dependencias(): Map<String, Set<String>> =
        fuentes
            .listFiles()!!
            .filter { it.isDirectory }
            .associate { paquete -> paquete.name to importados(paquete) }

    private fun importados(paquete: File): Set<String> =
        paquete
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { fuente -> IMPORT.findAll(fuente.readText()).map { it.groupValues[1] } }
            .filter { it != paquete.name }
            .toSet()

    private companion object {
        val IMPORT = Regex("^import caja\\.(\\w+)\\.", RegexOption.MULTILINE)

        // los ciclos del grafo: cada uno con sus paquetes, los que se alcanzan unos a otros, por nombre
        fun ciclos(grafo: Map<String, Set<String>>): List<List<String>> {
            val paquetes = grafo.keys
            val alcance = paquetes.associateWith { alcanzables(grafo, it) }
            return paquetes
                .filter { it in alcance.getValue(it) }
                .map { v -> paquetes.filter { w -> w in alcance.getValue(v) && v in alcance.getValue(w) }.sorted() }
                .distinct()
                .sortedBy { it.first() }
        }

        // los paquetes a los que se llega desde uno, siguiendo sus imports
        fun alcanzables(
            grafo: Map<String, Set<String>>,
            desde: String
        ): Set<String> {
            val vistos = mutableSetOf<String>()
            val pendientes = ArrayDeque(grafo[desde].orEmpty())
            while (pendientes.isNotEmpty()) {
                val paquete = pendientes.removeFirst()
                if (vistos.add(paquete)) pendientes += grafo[paquete].orEmpty()
            }
            return vistos
        }
    }
}
