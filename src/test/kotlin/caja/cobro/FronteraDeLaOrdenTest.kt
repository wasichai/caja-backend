package caja.cobro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.File

// la frontera de caja (CLAUDE.md de caja, «Lo que este repositorio NO hace»): una orden de cobro no sabe qué es un
// tributo. el día que gane tributo, ejercicio o periodo, la caja deja de servir para cobrar un puesto de mercado
class FronteraDeLaOrdenTest {
    private val prohibidos = listOf("tributo", "ejercicio", "periodo")

    @Test
    fun `la orden de cobro del modelo no tiene tributo, ejercicio ni periodo`() {
        val orden =
            JsonMapper
                .builder()
                .build()
                .readTree(File("model/model.json"))["objects"]
                .firstOrNull { it["name"].asString() == "orden_de_cobro" }
        assertNotNull(orden, "model.json no tiene orden_de_cobro")
        val campos: List<String> =
            orden!!["fields"]
                .iterator()
                .asSequence()
                .map { it["name"].asString() }
                .toList()
        assertEquals(emptyList<String>(), campos.filter { campo -> prohibidos.any { campo.startsWith(it) } }, campos.toString())
    }
}
