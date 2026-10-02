package caja.recaudacion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// regla 6: las agregaciones de la recaudación y la línea de la conciliación son funciones puras. sin Spring, sin reloj,
// sin base y sin red: el día entra como argumento, y la conciliación del 2 de octubre da lo mismo hoy que dentro de diez
// años. la prueba lee sus fuentes y falla si alguna importa o llama algo que lo rompa (como PurezaDelTurnoTest)
class PurezaDeLaRecaudacionTest {
    private val puras = listOf("Recaudacion.kt", "Conciliacion.kt").map { File("src/main/kotlin/caja/recaudacion/$it") }

    private val prohibido =
        Regex(
            "\\b(org\\.springframework|java\\.time\\.Clock|Clock|now\\(|Registros|RecordService|DatabaseClient|r2dbc|" +
                "wasichai\\.core\\.data|kotlinx\\.coroutines|suspend|HttpClient|java\\.net|Double|Float|RoundingMode|setScale|divide)\\b"
        )

    @Test
    fun `la recaudacion y la conciliacion no dependen de Spring, ni del reloj, ni de la base, ni de la red`() {
        puras.forEach { assertTrue(it.isFile, "no está ${it.path}") }

        val hallazgos =
            puras.flatMap { fuente ->
                fuente.readLines().mapIndexedNotNull { i, linea ->
                    val codigo = linea.substringBefore("//")
                    if (prohibido.containsMatchIn(codigo)) "${fuente.name}:${i + 1}: ${linea.trim()}" else null
                }
            }

        assertEquals(emptyList<String>(), hallazgos)
    }

    @Test
    fun `el vigia reconoce lo prohibido y deja pasar lo puro`() {
        assertTrue(prohibido.containsMatchIn("import java.net.http.HttpClient"))
        assertTrue(prohibido.containsMatchIn("val hoy = LocalDate.now(reloj)"))
        assertTrue(prohibido.containsMatchIn("suspend fun leer()"))
        assertTrue(!prohibido.containsMatchIn("fun lineaDe(recuento: RecuentoDelDia, dia: LocalDate, lectura: Lectura)"))
        assertTrue(!prohibido.containsMatchIn("import java.math.BigDecimal"))
    }
}
