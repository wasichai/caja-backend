package caja.cobro

import caja.CajaApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// GET /api/caja/cajas: el catálogo de ventanillas, con su área
class CajasApiTest : CajaApiTest() {
    // la serie de cada caja creada, por código
    private val series = mutableMapOf<String, String>()

    @Test
    fun `lista las cajas con su area, y la de baja sale y se distingue`() {
        val area = "A-${unico()}"
        val areaId = registro("area", mapOf("codigo" to area, "nombre" to "TRAMITE DOCUMENTARIO", "activa" to true))
        val abierta = caja(areaId, activa = true)
        val deBaja = caja(areaId, activa = false)

        // la lee un cajero: el permiso de lectura sobre caja y sobre área le basta
        val cajas = listar(funcionario("CAJERO"))
        val fila = cajas.getValue(abierta)
        assertEquals("VENTANILLA $abierta", fila["nombre"].asString())
        assertEquals(area, fila["area_codigo"].asString())
        assertEquals("TRAMITE DOCUMENTARIO", fila["area_nombre"].asString())
        assertTrue(fila["activa"].asBoolean())
        assertEquals(series[abierta], fila["serie"].asString())
        assertEquals(false, cajas.getValue(deBaja)["activa"].asBoolean())
        assertEquals(area, cajas.getValue(deBaja)["area_codigo"].asString())
    }

    @Test
    fun `una caja sin area sale sin area`() {
        val tributaria = caja(null, activa = true)
        val fila = listar(token).getValue(tributaria)
        assertTrue(fila["area_codigo"].isNull, fila.toString())
        assertTrue(fila["area_nombre"].isNull, fila.toString())
    }

    @Test
    fun `sin permiso de lectura sobre caja da 403`() {
        val ajeno = funcionario(listOf(permiso("orden_de_cobro", "READ")))
        send("GET", "/api/caja/cajas", null, HttpStatus.FORBIDDEN, ajeno)
    }

    // una caja nueva: su código
    private fun caja(
        area: String?,
        activa: Boolean
    ): String {
        val codigo = "C-${unico()}"
        // cinco caracteres hexadecimales: única en la base compartida
        val serie = uniqueName("").uppercase().take(5)
        registro("caja", mapOf("codigo" to codigo, "nombre" to "VENTANILLA $codigo", "serie" to serie, "activa" to activa, "area" to area))
        series[codigo] = serie
        return codigo
    }

    private fun listar(token: String): Map<String, JsonNode> =
        tree(send("GET", "/api/caja/cajas?size=200", null, HttpStatus.OK, token))["content"]
            .iterator()
            .asSequence()
            .associateBy { it["codigo"].asString() }
}
