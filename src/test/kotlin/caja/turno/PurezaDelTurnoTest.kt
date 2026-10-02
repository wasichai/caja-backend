package caja.turno

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// regla 6: el arqueo y la máquina de estados del turno son funciones puras. sin Spring, sin reloj y sin base: la fecha
// entra como argumento, y el arqueo de un turno de 2026 vuelve a dar el mismo céntimo en 2036. la prueba lee sus
// fuentes y falla si alguna importa o llama algo que lo rompa
class PurezaDelTurnoTest {
    private val puras = listOf("ArqueoDelTurno.kt", "CierreDeTurno.kt").map { File("src/main/kotlin/caja/turno/$it") }

    // lo que no puede aparecer: Spring, el reloj (Clock o un now()), la base (Registros, RecordService, DatabaseClient,
    // r2dbc, los datos de core), corrutinas o un suspend (que solo existe para esperar a la base)
    private val prohibido =
        Regex(
            "\\b(org\\.springframework|java\\.time\\.Clock|Clock|now\\(|Registros|RecordService|DatabaseClient|r2dbc|" +
                "wasichai\\.core\\.data|kotlinx\\.coroutines|suspend|Double|Float|RoundingMode|setScale|divide)\\b"
        )

    @Test
    fun `el arqueo y el cierre no dependen de Spring, ni del reloj, ni de la base`() {
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
        assertTrue(prohibido.containsMatchIn("import org.springframework.stereotype.Component"))
        assertTrue(prohibido.containsMatchIn("val hoy = LocalDate.now(reloj)"))
        assertTrue(prohibido.containsMatchIn("suspend fun leer()"))
        assertTrue(prohibido.containsMatchIn("total.setScale(2, RoundingMode.HALF_UP)"))
        assertTrue(!prohibido.containsMatchIn("fun de(recibos: List<ReciboDelTurno>, aLaFecha: LocalDate)"))
        assertTrue(!prohibido.containsMatchIn("import java.math.BigDecimal"))
    }
}
