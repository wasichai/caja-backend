package caja.buzon

import caja.cobro.Vigencia
import caja.modelo.OrdenDeCobro
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

// las reglas puras de la comprobación en línea (caja-backend#42): qué se le pregunta al origen y qué quiere decir lo que
// contesta. una orden no vigente, o con otro importe, no se cobra; lo que no se entiende es que el origen no contestó
class VigenciaPorHttpTest {
    private val ordenes = listOf(orden("o1", "ARB-1|LIMP|2026|3|1|2", "12.50"), orden("o2", "PRED-2", "100.00"))

    @Test
    fun `la pregunta lleva cada referencia con su importe en cadena`() {
        val pregunta = JSON.readTree(preguntaDeVigencia(ordenes))
        assertEquals(2, pregunta["ordenes"].size())
        assertEquals("ARB-1|LIMP|2026|3|1|2", pregunta["ordenes"][0]["referencia_externa"].asString())
        assertEquals("12.50", pregunta["ordenes"][0]["importe"].asString())
        assertTrue(pregunta["ordenes"][0]["importe"].isString)
    }

    @Test
    fun `todo vigente contesta sin no vigentes`() {
        val vigencia = vigenciaDe(ordenes, contesto(vigente("ARB-1|LIMP|2026|3|1|2", "12.50"), vigente("PRED-2", null)), cobrar = true)
        assertEquals(Vigencia.Contesto(emptyMap()), vigencia)
    }

    @Test
    fun `el mismo importe con otra escala sigue vigente`() {
        val vigencia = vigenciaDe(ordenes, contesto(vigente("ARB-1|LIMP|2026|3|1|2", "12.5"), vigente("PRED-2", "100")), cobrar = true)
        assertEquals(Vigencia.Contesto(emptyMap()), vigencia)
    }

    @Test
    fun `una no vigente lleva el motivo del origen`() {
        val vigencia =
            vigenciaDe(
                ordenes,
                contesto(
                    """{"referencia_externa":"ARB-1|LIMP|2026|3|1|2","vigente":false,"importe":null,"motivo":"Redeterminada por la DJ 12"}""",
                    vigente("PRED-2", "100.00")
                ),
                cobrar = true
            )
        assertEquals(Vigencia.Contesto(mapOf("o1" to "el origen dice: Redeterminada por la DJ 12")), vigencia)
    }

    @Test
    fun `una no vigente sin motivo lo dice`() {
        val vigencia =
            vigenciaDe(ordenes, contesto("""{"referencia_externa":"PRED-2","vigente":false}""", vigente("ARB-1|LIMP|2026|3|1|2", null)), cobrar = true)
        assertEquals(Vigencia.Contesto(mapOf("o2" to "el origen dice: ya no está vigente")), vigencia)
    }

    @Test
    fun `otro importe no se cobra y dice cuanto`() {
        val vigencia = vigenciaDe(ordenes, contesto(vigente("ARB-1|LIMP|2026|3|1|2", "13.00"), vigente("PRED-2", "100.00")), cobrar = true)
        assertEquals(Vigencia.Contesto(mapOf("o1" to "el origen dice otro importe: 13.00")), vigencia)
    }

    @Test
    fun `el motivo pierde los caracteres de control y no pasa de 200`() {
        val largo = "a".repeat(300)
        val vigencia =
            vigenciaDe(
                ordenes,
                contesto(
                    """{"referencia_externa":"ARB-1|LIMP|2026|3|1|2","vigente":false,"motivo":"una\nlinea\t$largo"}""",
                    vigente("PRED-2", null)
                ),
                cobrar = true
            ) as Vigencia.Contesto
        val motivo = vigencia.noVigentes.getValue("o1")
        assertTrue(motivo.startsWith("el origen dice: una linea aaa"), motivo)
        assertEquals(200, motivo.codePointCount(0, motivo.length))
        assertTrue(motivo.endsWith("…"), motivo)
        assertTrue(motivo.codePoints().noneMatch(Character::isISOControl), motivo)
    }

    @Test
    fun `una referencia que falta en la respuesta es que no contesta`() {
        val vigencia = vigenciaDe(ordenes, contesto(vigente("PRED-2", null)), cobrar = true)
        val noContesta = assertInstanceOf(Vigencia.NoContesta::class.java, vigencia)
        assertTrue(noContesta.motivo.contains("ARB-1|LIMP|2026|3|1|2"), noContesta.motivo)
        assertTrue(noContesta.cobrar)
    }

    @Test
    fun `lo ilegible es que no contesta, y la configuracion dice si se cobra`() {
        listOf(
            Lectura.Contesto(200, "no es json"),
            Lectura.Contesto(200, """{"ordenes":"nada"}"""),
            Lectura.Contesto(200, """{"ordenes":[{"referencia_externa":"ARB-1|LIMP|2026|3|1|2","vigente":"sí"}]}"""),
            Lectura.Contesto(200, """{"ordenes":[{"referencia_externa":"ARB-1|LIMP|2026|3|1|2","vigente":true,"importe":"doce"}]}"""),
            Lectura.Contesto(500, ""),
            Lectura.Contesto(404, """{"ordenes":[]}"""),
            Lectura.NoContesta("ConnectException: refused"),
            Lectura.SinDireccion
        ).forEach { lectura ->
            assertEquals(false, (vigenciaDe(ordenes, lectura, cobrar = false) as Vigencia.NoContesta).cobrar, "$lectura")
            assertEquals(true, (vigenciaDe(ordenes, lectura, cobrar = true) as Vigencia.NoContesta).cobrar, "$lectura")
        }
    }

    @Test
    fun `un codigo que no es 200 se nombra en el motivo`() {
        val noContesta = vigenciaDe(ordenes, Lectura.Contesto(503, ""), cobrar = true) as Vigencia.NoContesta
        assertTrue(noContesta.motivo.contains("503"), noContesta.motivo)
    }

    private fun orden(
        id: String,
        referencia: String,
        importe: String
    ) = OrdenDeCobro(id = id, sistemaOrigen = "rentas", referenciaExterna = referencia, importe = BigDecimal(importe))

    private fun vigente(
        referencia: String,
        importe: String?
    ) = """{"referencia_externa":"$referencia","vigente":true,"importe":${importe?.let { "\"$it\"" } ?: "null"},"motivo":null}"""

    private fun contesto(vararg ordenes: String) = Lectura.Contesto(200, """{"ordenes":[${ordenes.joinToString(",")}]}""")

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
