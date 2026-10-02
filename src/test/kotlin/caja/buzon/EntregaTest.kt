package caja.buzon

import caja.cobro.LineaRecibo
import caja.cobro.OrdenDeCobro
import caja.cobro.Recibo
import caja.cobro.cuerpoPagoRegistrado
import caja.recibo.cuerpoPagoAnulado
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

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
        private val registradoId = UUID.fromString("3e6da681-2467-48e6-acc7-281903b9b578")
        private val anuladoId = UUID.fromString("9b2f0d4e-1c3a-4e5f-8a7b-6c5d4e3f2a1b")
        private val hoy = LocalDate.parse("2026-10-02")
        private val recibo =
            Recibo(
                id = "r1",
                serie = "001",
                numeroImpreso = "001-0000001",
                cajero = "ana@muni.gob.pe",
                pagadorDocumento = "12345678",
                pagadorNombre = "FLORES OTINIANO JUNIOR",
                pagadorExternoId = 1234,
                formaPago = "EFECTIVO",
                tipoPago = "NORMAL",
                total = BigDecimal("230.75"),
                actualizadoA = hoy
            )
        private val lineas =
            listOf(
                LineaRecibo(orden = orden1, sistemaOrigen = "rentas", referenciaExterna = "PREDIAL-1", monto = BigDecimal("150.50")),
                LineaRecibo(orden = orden2, sistemaOrigen = "rentas", referenciaExterna = "PREDIAL-2", monto = BigDecimal("80.25"))
            )
        private val actualizado = mapOf(orden1 to LocalDate.parse("2026-03-15"), orden2 to LocalDate.parse("2026-03-16"))
        private val eventos = listOf(EventoDelRecibo(registradoId, registradoId.toString(), "PAGO_REGISTRADO"))
        private val delRecibo = ReciboDelEvento(recibo, lineas, actualizado, null, eventos)

        // el cuerpo que escribió la cobranza: el mismo compositor, con las órdenes de la petición
        private val legitimo =
            cuerpoPagoRegistrado(
                registradoId,
                recibo,
                lineas.map {
                    OrdenDeCobro(
                        id = it.orden,
                        sistemaOrigen = "rentas",
                        referenciaExterna = it.referenciaExterna,
                        importe = it.monto,
                        actualizadoA = actualizado[it.orden]
                    )
                }
            )

        private fun evento(
            cuerpo: String,
            tipo: String = "PAGO_REGISTRADO",
            id: UUID = registradoId,
            sistema: String = "rentas"
        ) = EventoDelBuzon(id, Instant.EPOCH, id.toString(), tipo, sistema, "r1", "t1", cuerpo, 0)

        private fun cambiado(
            cuerpo: String,
            cambio: (ObjectNode) -> Unit
        ): String = (JSON.readTree(cuerpo) as ObjectNode).also(cambio).toString()

        @Test
        fun `el PAGO_REGISTRADO que escribio la cobranza coincide, aunque sus ordenes vengan en otro orden`() {
            assertNull(incoherencia(evento(legitimo), delRecibo))
            assertNull(incoherencia(evento(legitimo), delRecibo.copy(lineas = lineas.reversed())))
        }

        @Test
        fun `sin recibo no coincide`() {
            assertTrue(incoherencia(evento(legitimo), null)!!.contains("no existe"))
        }

        @Test
        fun `una copia con otro pagoId no coincide, aunque su cuerpo diga lo mismo`() {
            val otro = UUID.randomUUID()
            val copia = evento(legitimo.replace(registradoId.toString(), otro.toString()), id = otro)
            val conLaCopia = delRecibo.copy(eventos = eventos + EventoDelRecibo(otro, otro.toString(), "PAGO_REGISTRADO"))
            assertTrue(incoherencia(copia, conLaCopia)!!.contains("copia"))
            // y el legítimo, que es el primero de su tipo, sigue coincidiendo
            assertNull(incoherencia(evento(legitimo), conLaCopia))
        }

        @Test
        fun `el pagoId del cuerpo es el evento_id de la fila`() {
            assertTrue(incoherencia(evento(legitimo.replace(registradoId.toString(), UUID.randomUUID().toString())), delRecibo)!!.contains("pagoId"))
        }

        @Test
        fun `otra referencia, el importe repartido de otro modo con el mismo total, u otra fecha de la orden no coinciden`() {
            val referencia = legitimo.replace("PREDIAL-2", "PREDIAL-9")
            assertTrue(incoherencia(evento(referencia), delRecibo)!!.contains("ordenes"))
            val repartido = legitimo.replace("\"150.50\"", "\"200.50\"").replace("\"80.25\"", "\"30.25\"")
            assertTrue(repartido != legitimo)
            assertTrue(incoherencia(evento(repartido), delRecibo)!!.contains("ordenes"))
            assertTrue(incoherencia(evento(legitimo.replace("2026-03-16", "2026-03-17")), delRecibo)!!.contains("ordenes"))
        }

        @Test
        fun `otro total, otro pagador u otro sistema no coinciden`() {
            assertTrue(incoherencia(evento(cambiado(legitimo) { it.put("total", "999.99") }), delRecibo)!!.contains("total"))
            assertTrue(incoherencia(evento(cambiado(legitimo) { (it["pagador"] as ObjectNode).put("idExterno", 99) }), delRecibo)!!.contains("pagador"))
            assertTrue(incoherencia(evento(legitimo, sistema = "mercados"), delRecibo)!!.contains("sistema"))
            assertTrue(incoherencia(evento(cambiado(legitimo) { it.put("sistemaOrigen", "mercados") }), delRecibo)!!.contains("sistemaOrigen"))
        }

        @Test
        fun `un PAGO_REGISTRADO de un recibo de tasas, o de una orden que ya no existe, no coincide`() {
            assertTrue(incoherencia(evento(legitimo), delRecibo.copy(recibo = recibo.copy(tipoPago = "TASA")))!!.contains("NORMAL"))
            assertTrue(
                incoherencia(evento(legitimo), delRecibo.copy(actualizadoDeLasOrdenes = mapOf(orden1 to LocalDate.parse("2026-03-15"))))!!.contains(orden2)
            )
        }

        @Test
        fun `un PAGO_ANULADO coincide solo con su anulacion y el pagoId del PAGO_REGISTRADO que deshace`() {
            val anulado = cuerpoPagoAnulado(anuladoId, registradoId.toString(), recibo, "COBRO EN DEMASÍA", hoy)
            val conAnulacion =
                delRecibo.copy(
                    anulacion = AnulacionDelEvento("COBRO EN DEMASÍA", hoy),
                    eventos = eventos + EventoDelRecibo(anuladoId, anuladoId.toString(), "PAGO_ANULADO")
                )
            val delAnulado = evento(anulado, tipo = "PAGO_ANULADO", id = anuladoId)
            assertNull(incoherencia(delAnulado, conAnulacion))
            assertTrue(incoherencia(delAnulado, conAnulacion.copy(anulacion = null))!!.contains("anulacion_recibo"))
            val otroOriginal = anulado.replace(registradoId.toString(), UUID.randomUUID().toString())
            assertTrue(incoherencia(evento(otroOriginal, tipo = "PAGO_ANULADO", id = anuladoId), conAnulacion)!!.contains("pagoOriginalId"))
            assertTrue(
                incoherencia(evento(anulado.replace("COBRO EN DEMASÍA", "OTRO"), tipo = "PAGO_ANULADO", id = anuladoId), conAnulacion)!!.contains("motivo")
            )
        }

        @Test
        fun `un cuerpo que no es json, o que dice otro tipo que la fila, no coincide`() {
            assertTrue(incoherencia(evento("no es json"), delRecibo)!!.contains("JSON"))
            assertTrue(incoherencia(evento(cambiado(legitimo) { it.put("tipo", "PAGO_ANULADO") }), delRecibo)!!.contains("tipo"))
            assertTrue(
                incoherencia(
                    evento(legitimo, tipo = "OTRO"),
                    delRecibo.copy(eventos = listOf(EventoDelRecibo(registradoId, registradoId.toString(), "OTRO")))
                )!!.contains("tipo")
            )
        }
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
