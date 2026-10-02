package caja.comun

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import wasichai.core.data.RecordResponse
import java.math.BigDecimal
import java.time.Instant

// el orden de lo que lee Registros.all: las páginas van por id (único), y el orden pedido se aplica después, sobre todo
// lo leído, como lo haría postgres y con el id como desempate. RecaudacionApiTest prueba contra la base que con las
// páginas por created_at una línea salía dos veces y otra ninguna
class RegistrosTest {
    private val mismoSello = Instant.parse("2026-10-02T15:00:00.123456Z")

    private fun fila(
        id: String,
        creado: Instant? = mismoSello,
        vararg atributos: Pair<String, Any?>
    ) = RecordResponse(id, creado, null, mapOf(*atributos))

    @Test
    fun `sin orden pedido se queda el de las paginas`() {
        val filas = listOf(fila("c"), fila("a"), fila("b"))

        assertEquals(listOf("c", "a", "b"), Registros.enOrden(filas, null, false).map { it.id })
    }

    @Test
    fun `por created_at, los empatados salen por id, y lo mas antiguo primero`() {
        val filas = listOf(fila("c"), fila("b"), fila("z", mismoSello.minusSeconds(1)), fila("a"))

        assertEquals(listOf("z", "a", "b", "c"), Registros.enOrden(filas, "created_at", false).map { it.id })
        assertEquals(listOf("c", "b", "a", "z"), Registros.enOrden(filas, "created_at", true).map { it.id })
    }

    @Test
    fun `por un campo, con los null al final al subir y al principio al bajar, como postgres`() {
        val filas =
            listOf(
                fila("a", null, "codigo" to "T-2"),
                fila("b", null, "codigo" to null),
                fila("c", null, "codigo" to "T-1"),
                fila("d", null, "codigo" to "T-1")
            )

        assertEquals(listOf("c", "d", "a", "b"), Registros.enOrden(filas, "codigo", false).map { it.id })
        assertEquals(listOf("b", "a", "d", "c"), Registros.enOrden(filas, "codigo", true).map { it.id })
    }

    @Test
    fun `una cifra se ordena como numero, no como texto`() {
        val filas = listOf(fila("a", null, "total" to BigDecimal("10.00")), fila("b", null, "total" to BigDecimal("9.50")))

        assertEquals(listOf("b", "a"), Registros.enOrden(filas, "total", false).map { it.id })
    }
}
