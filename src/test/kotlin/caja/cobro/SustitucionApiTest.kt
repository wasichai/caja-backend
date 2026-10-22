package caja.cobro

import caja.CajaApiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// el alta con sustituye_a (caja-backend#40): un origen que redetermina manda la versión nueva de la deuda con otra
// referencia y nombra la que reemplaza. en un solo paso, la nueva nace PENDIENTE y la vieja queda ANULADA con
// sustituida_por; una vieja PAGADA no se sustituye (409) y entonces no nace nada
class SustitucionApiTest : CajaApiTest() {
    // el pagador de cada prueba: junit crea una instancia por prueba, y la lista por documento solo ve las suyas
    private val documento = "4${unico().take(7)}"

    @Test
    fun `sustituir una PENDIENTE`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val (v1, v2) = versiones()
        val vieja = alta(rentas, v1)

        val nueva = tree(send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.CREATED, rentas))

        assertTrue(nueva["nueva"].asBoolean())
        assertEquals("PENDIENTE", nueva["estado"].asString())
        assertEquals(v1, nueva["sustituye_a"].asString())
        assertTrue(nueva["sustituida_por"].isNull, nueva.toString())
        val guardada = atributos(vieja["orden_id"].asString())
        assertEquals("ANULADA", guardada["estado"].asString())
        assertEquals("Sustituida por $v2", guardada["motivo_retiro"].asString())
        assertEquals(v2, guardada["sustituida_por"].asString())
        assertTrue(!guardada["retirada_en"].isNull, guardada.toString())
        // la ventanilla ve la vieja anulada con quién la sustituyó
        val anuladas =
            tree(send("GET", "$ORDENES?pagador_documento=${vieja["pagador_documento"].asString()}&estado=ANULADA", null, HttpStatus.OK))
        assertEquals(v2, anuladas["content"][0]["sustituida_por"].asString(), anuladas.toString())
    }

    @Test
    fun `el mismo importe tambien se sustituye`() {
        // en srtm cada redeterminación es una versión nueva aunque el monto no cambie: la zona, el parámetro o el
        // contribuyente pueden ser otros
        val (_, rentas) = cuentaDeServicio("rentas")
        val (v1, v2) = versiones()
        alta(rentas, v1)

        val nueva = tree(send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.CREATED, rentas))

        assertEquals("150.50", nueva["importe"]["importe"].asString())
    }

    @Test
    fun `reenviar la misma sustitucion es 200 nueva false y no cambia nada`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val (v1, v2) = versiones()
        val vieja = alta(rentas, v1)
        val nueva = tree(send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.CREATED, rentas))
        val antes = atributos(vieja["orden_id"].asString())

        val reenvio = tree(send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.OK, rentas))

        assertEquals(nueva["orden_id"].asString(), reenvio["orden_id"].asString())
        assertEquals(false, reenvio["nueva"].asBoolean())
        assertEquals(antes, atributos(vieja["orden_id"].asString()))
        assertEquals(1, registros("orden_de_cobro", "referencia_externa" to v2).size)
        // el reenvío con otro importe choca como cualquier alta, y el que nombra otra vieja también
        send("POST", ORDENES, cuerpo(v2, sustituyeA = v1) + ("importe" to "1.00"), HttpStatus.CONFLICT, rentas)
        val otra = alta(rentas, "${v1}x")
        val problema = tree(send("POST", ORDENES, cuerpo(v2, sustituyeA = "${v1}x"), HttpStatus.CONFLICT, rentas))
        assertTrue(problema["detail"].asString().contains("sustituye_a"), problema.toString())
        assertEquals("PENDIENTE", atributos(otra["orden_id"].asString())["estado"].asString())
    }

    @Test
    fun `sustituir una PAGADA es 409 y no crea nada`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val (v1, v2) = versiones()
        val vieja = alta(rentas, v1)
        val ordenId = vieja["orden_id"].asString()
        post(COBROS, cobro(nuevaCaja(), ordenId), funcionario("CAJERO"))

        val problema = tree(send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.CONFLICT, rentas))

        assertTrue(problema["detail"].asString().contains(v1), problema.toString())
        assertEquals("PAGADA", atributos(ordenId)["estado"].asString())
        assertTrue(registros("orden_de_cobro", "referencia_externa" to v2).isEmpty())
    }

    @Test
    fun `sustituir una que no existe es 404 en sustituye_a`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val (v1, v2) = versiones()

        val problema = tree(send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.NOT_FOUND, rentas))

        assertTrue(problema["detail"].asString().contains("sustituye_a"), problema.toString())
        assertTrue(problema["detail"].asString().contains(v1), problema.toString())
        assertTrue(registros("orden_de_cobro", "referencia_externa" to v2).isEmpty())
    }

    @Test
    fun `la vieja de otro sistema tampoco se encuentra`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val (_, mercados) = cuentaDeServicio("mercados")
        val (v1, v2) = versiones()
        val vieja = alta(rentas, v1)

        send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.NOT_FOUND, mercados)

        assertEquals("PENDIENTE", atributos(vieja["orden_id"].asString())["estado"].asString())
    }

    @Test
    fun `sustituir una ya ANULADA sin sustituta crea la nueva`() {
        // el caso de srtm cuando retiró primero (la redeterminación) y manda la versión nueva después
        val (_, rentas) = cuentaDeServicio("rentas")
        val (v1, v2) = versiones()
        val vieja = alta(rentas, v1)
        send("POST", RETIRO, mapOf("referencia_externa" to v1, "motivo" to "Redeterminación por la DJ n° 12"), HttpStatus.OK, rentas)

        val nueva = tree(send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.CREATED, rentas))

        assertEquals(v1, nueva["sustituye_a"].asString())
        val guardada = atributos(vieja["orden_id"].asString())
        assertEquals("ANULADA", guardada["estado"].asString())
        // el retiro manda: su motivo se queda, y la sustitución solo anota quién la reemplaza
        assertEquals("Redeterminación por la DJ n° 12", guardada["motivo_retiro"].asString())
        assertEquals(v2, guardada["sustituida_por"].asString())
    }

    @Test
    fun `una ya sustituida por otra es 409 y no crea nada`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val (v1, v2) = versiones()
        alta(rentas, v1)
        send("POST", ORDENES, cuerpo(v2, sustituyeA = v1), HttpStatus.CREATED, rentas)

        val problema = tree(send("POST", ORDENES, cuerpo("${v2}b", sustituyeA = v1), HttpStatus.CONFLICT, rentas))

        assertTrue(problema["detail"].asString().contains(v2), problema.toString())
        assertTrue(registros("orden_de_cobro", "referencia_externa" to "${v2}b").isEmpty())
    }

    @Test
    fun `sustituirse a si misma es 400 en sustituye_a`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val (v1, _) = versiones()

        rejected("POST", ORDENES, cuerpo(v1, sustituyeA = " $v1 "), "sustituye_a", rentas)
        rejected("POST", ORDENES, cuerpo(v1, sustituyeA = "x".repeat(121)), "sustituye_a", rentas)
        assertTrue(registros("orden_de_cobro", "referencia_externa" to v1).isEmpty())
    }

    @Test
    fun `dos sustituciones iguales a la vez dan una sola nueva`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        repeat(3) {
            val (v1, v2) = versiones()
            alta(rentas, v1)

            val respuestas =
                runBlocking {
                    coroutineScope {
                        (1..4).map { async(Dispatchers.IO) { exchange("POST", ORDENES, cuerpo(v2, sustituyeA = v1), rentas) } }.awaitAll()
                    }
                }

            val estados = respuestas.map { it.first }
            assertEquals(1, estados.count { it == HttpStatus.CREATED }, respuestas.toString())
            assertEquals(3, estados.count { it == HttpStatus.OK }, respuestas.toString())
            assertEquals(1, respuestas.map { tree(it.second)["orden_id"].asString() }.toSet().size, respuestas.toString())
            assertEquals(1, registros("orden_de_cobro", "referencia_externa" to v2).size)
        }
    }

    @Test
    fun `sustitucion y cobro de la vieja a la vez - gana uno`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        repeat(3) {
            val (v1, v2) = versiones()
            val vieja = alta(rentas, v1)
            val ordenId = vieja["orden_id"].asString()
            val caja = nuevaCaja()
            val cajero = funcionario("CAJERO")

            val (cobro, sustitucion) =
                runBlocking {
                    coroutineScope {
                        listOf(
                            async(Dispatchers.IO) { exchange("POST", COBROS, cobro(caja, ordenId), cajero) },
                            async(Dispatchers.IO) { exchange("POST", ORDENES, cuerpo(v2, sustituyeA = v1), rentas) }
                        ).awaitAll()
                    }
                }

            val estados = cobro.first to sustitucion.first
            val guardada = atributos(ordenId)
            val nuevas = registros("orden_de_cobro", "referencia_externa" to v2)
            when (estados) {
                HttpStatus.CREATED to HttpStatus.CONFLICT -> {
                    assertEquals("PAGADA", guardada["estado"].asString())
                    assertTrue(nuevas.isEmpty(), nuevas.toString())
                }
                HttpStatus.UNPROCESSABLE_CONTENT to HttpStatus.CREATED -> {
                    assertEquals("ANULADA", guardada["estado"].asString())
                    assertEquals(v2, guardada["sustituida_por"].asString())
                    assertEquals(1, nuevas.size)
                    assertTrue(registros("linea_recibo", "orden" to ordenId).isEmpty())
                }
                else -> throw AssertionError("cobro $cobro, sustitución $sustitucion")
            }
        }
    }

    // dos versiones de una cuota de arbitrios de srtm: la clave termina en la versión, y la referencia de caja es
    // ARB-{clave}, una por versión
    private fun versiones(): Pair<String, String> {
        val clave = "ARB-p${unico()}|LIMP|2026|3|1"
        return "$clave|1" to "$clave|2"
    }

    private fun cuerpo(
        referencia: String,
        sustituyeA: String? = null
    ): Map<String, Any?> {
        val cuerpo = orden("referencia_externa" to referencia, "pagador_documento" to documento) - "sistema_origen"
        return if (sustituyeA == null) cuerpo else cuerpo + ("sustituye_a" to sustituyeA)
    }

    private fun alta(
        cuenta: String,
        referencia: String
    ): JsonNode = post(ORDENES, cuerpo(referencia), cuenta)

    private fun atributos(orden: String): JsonNode = tree(send("GET", "/api/objects/orden_de_cobro/records/$orden", null, HttpStatus.OK))["attributes"]

    private fun cobro(
        caja: CajaDePrueba,
        vararg ordenes: String
    ): Map<String, Any?> =
        mapOf(
            "caja" to caja.codigo,
            "forma_pago" to "EFECTIVO",
            "ordenes" to ordenes.toList(),
            "observacion" to "cobro en ventanilla"
        )

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val RETIRO = "/api/caja/ordenes-de-cobro/retiro"
        const val COBROS = "/api/caja/cobros"
    }
}
