package caja.buzon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

// las reglas puras del publicador (ClienteHttpDelSistemaDeOrigen.publicar, EntregarEventos y UnPagoNoMuereSinCredencialTest
// de caja): qué es cada respuesta del destino, cuánto cabe en ultimo_error, qué marca deja cada intento y cuándo un
// evento no coincide con su recibo
class EntregaTest {
    @Nested
    inner class LaClasificacion {
        @Test
        fun `200, 201, 202 y 409 son entregado, y el 409 es que el receptor ya lo tenia`() {
            listOf(200, 201, 202, 409).forEach { estado ->
                assertEquals(Respuesta.Entregado, clasificar("rentas", estado, "", token = null), "$estado")
            }
        }

        @Test
        fun `un 401 sin credencial no contesta y dice que falta el token`() {
            val respuesta = clasificar("rentas", 401, "", token = null)
            assertInstanceOf(Respuesta.NoContesta::class.java, respuesta)
            val motivo = (respuesta as Respuesta.NoContesta).motivo
            assertTrue(motivo.contains("401"), motivo)
            assertTrue(motivo.contains("caja.buzon.destinos.rentas.token"), motivo)
            assertTrue(motivo.contains("se reintenta"), motivo)
        }

        @Test
        fun `un 401 con credencial y un 403 no contestan, y dicen cosas distintas`() {
            val caducada = clasificar("rentas", 401, "", token = "el-token") as Respuesta.NoContesta
            val sinPermiso = clasificar("rentas", 403, "", token = "el-token") as Respuesta.NoContesta
            assertTrue(caducada.motivo.contains("no vale o caducó"), caducada.motivo)
            assertTrue(sinPermiso.motivo.contains("permiso"), sinPermiso.motivo)
            assertFalse(sinPermiso.motivo.contains("no vale o caducó"), sinPermiso.motivo)
        }

        @Test
        fun `otro 4xx es un rechazo, que no se reintenta, y dice lo que contesto`() {
            listOf(400, 404, 422).forEach { estado ->
                val respuesta = clasificar("rentas", estado, """{"detail":"la orden no existe"}""", token = null)
                assertInstanceOf(Respuesta.Rechazado::class.java, respuesta, "$estado")
                assertTrue((respuesta as Respuesta.Rechazado).motivo.contains("la orden no existe"), respuesta.motivo)
            }
        }

        @Test
        fun `un 5xx o un estado raro no contesta`() {
            listOf(500, 502, 503, 302, 100).forEach { estado ->
                assertInstanceOf(Respuesta.NoContesta::class.java, clasificar("rentas", estado, "", token = null), "$estado")
            }
        }

        @Test
        fun `sin url configurada no contesta, y nombra la propiedad`() {
            val motivo = sinDireccion("mercados").motivo
            assertTrue(motivo.contains("caja.buzon.destinos.mercados.url"), motivo)
        }

        @Test
        fun `lo que contesto el destino viaja sin el token`() {
            val eco = """{"error":"forbidden","peticion":{"Authorization":"Bearer el-token-secreto"}}"""
            val motivo = (clasificar("rentas", 403, eco, token = "el-token-secreto") as Respuesta.NoContesta).motivo
            assertTrue(motivo.contains("forbidden"), motivo)
            assertFalse(motivo.contains("el-token-secreto"), motivo)
            val otro = (clasificar("rentas", 422, "Authorization: Bearer abc.def-123", token = null) as Respuesta.Rechazado).motivo
            assertFalse(otro.contains("abc.def-123"), otro)
        }
    }

    @Nested
    inner class ElRecorte {
        @Test
        fun `ultimo_error cabe en 400 caracteres y el corte se ve`() {
            val largo = recortar("x".repeat(5000))
            assertEquals(LARGO_ULTIMO_ERROR, largo.length)
            assertTrue(largo.endsWith("…"))
        }

        @Test
        fun `lo que cabe no se toca`() {
            assertEquals("corto", recortar("corto"))
            assertEquals("y".repeat(400), recortar("y".repeat(400)))
        }

        @Test
        fun `lo que se corta es la cola, el diagnostico va delante`() {
            val motivo = (clasificar("rentas", 403, "<html>" + "z".repeat(4000) + "</html>", token = "t") as Respuesta.NoContesta).motivo
            val guardado = recortar(motivo)
            assertEquals(LARGO_ULTIMO_ERROR, guardado.length)
            assertTrue(guardado.contains("permiso"), guardado)
        }
    }

    @Nested
    inner class LaMarca {
        @Test
        fun `entregado cuenta el intento`() {
            assertEquals(Marca.ENTREGADO, marcaDe(Respuesta.Entregado, intentosLeidos = 3, maximos = 8))
        }

        @Test
        fun `no contesta sigue PENDIENTE hasta que intentos mas uno llega al maximo`() {
            assertEquals(Marca.PENDIENTE, marcaDe(Respuesta.NoContesta("x"), intentosLeidos = 0, maximos = 8))
            assertEquals(Marca.PENDIENTE, marcaDe(Respuesta.NoContesta("x"), intentosLeidos = 6, maximos = 8))
            assertEquals(Marca.MUERTO, marcaDe(Respuesta.NoContesta("x"), intentosLeidos = 7, maximos = 8))
            assertEquals(Marca.MUERTO, marcaDe(Respuesta.NoContesta("x"), intentosLeidos = 0, maximos = 1))
        }

        @Test
        fun `un rechazo muere ya`() {
            assertEquals(Marca.MUERTO, marcaDe(Respuesta.Rechazado("x"), intentosLeidos = 0, maximos = 8))
        }
    }

    @Nested
    inner class LaCoherencia {
        private val orden1 = "8f0c2a8e-1f0e-4d0b-9a8e-3c1d2b4a5e6f"
        private val orden2 = "1b2c3d4e-1f0e-4d0b-9a8e-3c1d2b4a5e6f"
        private val recibo = ReciboDelEvento("r1", "001-0000001", "NORMAL", BigDecimal("230.75"), listOf(orden2, orden1), anulado = false)

        private fun registrado(
            total: String = "230.75",
            ordenes: List<String> = listOf(orden1, orden2),
            tipo: String = "PAGO_REGISTRADO"
        ) = """{"pagoId":"p","tipo":"$tipo","total":"$total","ordenes":[${ordenes.joinToString(",") { """{"ordenId":"$it","importe":"1.00"}""" }}]}"""

        private fun anulado(total: String = "230.75") = """{"pagoId":"q","tipo":"PAGO_ANULADO","pagoOriginalId":"p","total":"$total"}"""

        @Test
        fun `un PAGO_REGISTRADO que coincide con su recibo pasa, con las ordenes en otro orden y el total con otra escala`() {
            assertNull(incoherencia("PAGO_REGISTRADO", registrado(), recibo))
            assertNull(incoherencia("PAGO_REGISTRADO", registrado(total = "230.750"), recibo))
        }

        @Test
        fun `sin recibo no coincide`() {
            assertTrue(incoherencia("PAGO_REGISTRADO", registrado(), null)!!.contains("no existe"))
        }

        @Test
        fun `un PAGO_REGISTRADO de un recibo de tasas no coincide`() {
            assertTrue(incoherencia("PAGO_REGISTRADO", registrado(), recibo.copy(tipoPago = "TASA"))!!.contains("NORMAL"))
        }

        @Test
        fun `otro total no coincide`() {
            assertTrue(incoherencia("PAGO_REGISTRADO", registrado(total = "999.99"), recibo)!!.contains("total"))
            assertTrue(incoherencia("PAGO_REGISTRADO", registrado(total = "no es un numero"), recibo)!!.contains("total"))
        }

        @Test
        fun `otras ordenes no coinciden, ni una de mas ni una de menos ni una repetida`() {
            assertTrue(incoherencia("PAGO_REGISTRADO", registrado(ordenes = listOf(orden1)), recibo)!!.contains("ordenes"))
            assertTrue(incoherencia("PAGO_REGISTRADO", registrado(ordenes = listOf(orden1, orden2, orden2)), recibo)!!.contains("ordenes"))
            assertTrue(incoherencia("PAGO_REGISTRADO", registrado(ordenes = listOf(orden1, "otra")), recibo)!!.contains("ordenes"))
        }

        @Test
        fun `un PAGO_ANULADO pasa solo con su anulacion y su total`() {
            assertTrue(incoherencia("PAGO_ANULADO", anulado(), recibo)!!.contains("anulacion_recibo"))
            assertNull(incoherencia("PAGO_ANULADO", anulado(), recibo.copy(anulado = true)))
            assertTrue(incoherencia("PAGO_ANULADO", anulado(total = "1.00"), recibo.copy(anulado = true))!!.contains("total"))
        }

        @Test
        fun `un cuerpo que no es json, o que dice otro tipo que la fila, no coincide`() {
            assertTrue(incoherencia("PAGO_REGISTRADO", "no es json", recibo)!!.contains("JSON"))
            assertTrue(incoherencia("PAGO_REGISTRADO", registrado(tipo = "PAGO_ANULADO"), recibo)!!.contains("tipo"))
            assertTrue(incoherencia("OTRO", registrado(tipo = "OTRO"), recibo)!!.contains("tipo"))
        }
    }
}
