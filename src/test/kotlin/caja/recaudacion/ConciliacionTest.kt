package caja.recaudacion

import caja.buzon.Lectura
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// la conciliación del día, sin base y sin red (ConciliacionDelDia, BuzonDeSalidaJdbc.recuentoDe y AbonosAplicadosHttp de
// caja, ADR-0026 §3): lo que la caja sabe sola (el recuento del buzón), lo que dice el origen, la diferencia, cuándo
// cuadra una línea y el día, y por qué no se sabe cuando el origen no contesta. NUNCA UN CERO en lugar de un dato
class ConciliacionTest {
    private val dia = LocalDate.of(2026, 10, 2)

    @Nested
    inner class DelRecuento {
        @Test
        fun `una linea por sistema de destino, con lo que sale del buzon y de los totales de los recibos`() {
            val recuentos =
                recuentosDe(
                    listOf(
                        EventoDelDia("rentas", "PAGO_REGISTRADO", "ENTREGADO", dinero("150.50")),
                        EventoDelDia("rentas", "PAGO_REGISTRADO", "PENDIENTE", dinero("80.25")),
                        EventoDelDia("rentas", "PAGO_ANULADO", "MUERTO", dinero("80.25")),
                        EventoDelDia("mercados", "PAGO_REGISTRADO", "EXPLICADO", dinero("10.00"))
                    )
                )

            assertEquals(listOf("mercados", "rentas"), recuentos.map { it.sistema })
            val rentas = recuentos.single { it.sistema == "rentas" }
            assertEquals(2, rentas.registrados)
            assertEquals(1, rentas.anulados)
            assertEquals(1, rentas.enTransito)
            assertEquals(1, rentas.muertos)
            assertEquals(0, rentas.explicados)
            assertEquals("230.75", rentas.cobrado.toPlainString())
            assertEquals("80.25", rentas.anulado.toPlainString())
            assertEquals("150.50", rentas.neto.toPlainString())
            assertEquals(1, recuentos.single { it.sistema == "mercados" }.explicados)
        }

        @Test
        fun `un dia sin eventos no tiene lineas`() {
            assertTrue(recuentosDe(emptyList()).isEmpty())
        }
    }

    @Nested
    inner class DeLaLinea {
        @Test
        fun `la diferencia es el neto menos lo aplicado, y cuadra en cero sin nada pendiente ni rechazado`() {
            val linea = linea(recuento(), aplicado(importe = "100.00"))

            assertEquals("0.00", linea.diferencia!!.toPlainString())
            assertTrue(linea.cuadra())
            assertNull(linea.porQueNoSeSabe)
        }

        @Test
        fun `si el origen aplico de menos, la diferencia lo dice con su cifra y no cuadra`() {
            val linea = linea(recuento(), aplicado(importe = "40.00"))

            assertEquals("60.00", linea.diferencia!!.toPlainString())
            assertFalse(linea.cuadra())
        }

        @Test
        fun `un rechazo en el origen no cuadra aunque la cifra coincida`() {
            val linea = linea(recuento(), aplicado(importe = "100.00", rechazados = 1))

            assertEquals("0.00", linea.diferencia!!.toPlainString())
            assertFalse(linea.cuadra())
        }

        @Test
        fun `un pago en transito o muerto impide que cuadre, y uno explicado no`() {
            assertFalse(linea(recuento(enTransito = 1), aplicado(importe = "100.00")).cuadra())
            assertFalse(linea(recuento(muertos = 1), aplicado(importe = "100.00")).cuadra())
            assertTrue(linea(recuento(explicados = 1), aplicado(importe = "100.00")).cuadra())
        }

        @Test
        fun `sin lo que dice el origen no hay diferencia ni ceros, hay un motivo, y no cuadra`() {
            val linea = LineaDeConciliacion(recuento(), null, "rentas no contestó: ConnectException")

            assertNull(linea.aplicado)
            assertNull(linea.diferencia)
            assertEquals("rentas no contestó: ConnectException", linea.porQueNoSeSabe)
            assertFalse(linea.cuadra())
        }

        @Test
        fun `o el origen contesto o hay un motivo, nunca las dos cosas ni ninguna`() {
            assertThrows<IllegalArgumentException> { LineaDeConciliacion(recuento(), aplicado(importe = "1.00"), "y además un motivo") }
            assertThrows<IllegalArgumentException> { LineaDeConciliacion(recuento(), null, null) }
            assertThrows<IllegalArgumentException> { LineaDeConciliacion(recuento(), null, " ") }
        }

        @Test
        fun `el dia cuadra si cuadran todas sus lineas, y un dia sin cobros cuadra`() {
            val buena = linea(recuento(), aplicado(importe = "100.00"))
            val mala = LineaDeConciliacion(recuento(), null, "el destino rentas no está configurado")

            assertTrue(cuadraElDia(emptyList()))
            assertTrue(cuadraElDia(listOf(buena)))
            assertFalse(cuadraElDia(listOf(buena, mala)))
        }
    }

    @Nested
    inner class DeLoQueDiceElOrigen {
        @Test
        fun `un 200 con las cuatro cifras es lo que aplico el origen`() {
            val linea =
                lineaDe(
                    recuento(),
                    dia,
                    Lectura.Contesto(200, """{"fecha":"2026-10-02","recibidos":2,"aplicados":1,"rechazados":1,"importe_aplicado":"60.50"}""")
                )

            val aplicado = linea.aplicado!!
            assertEquals(2L, aplicado.recibidos)
            assertEquals(1L, aplicado.aplicados)
            assertEquals(1L, aplicado.rechazados)
            assertEquals("60.50", aplicado.importeAplicado.toPlainString())
            assertEquals("39.50", linea.diferencia!!.toPlainString())
            assertNull(linea.porQueNoSeSabe)
        }

        @Test
        fun `se lee tambien importeAplicado, como lo contesta hoy rentas`() {
            val linea = lineaDe(recuento(), dia, Lectura.Contesto(200, """{"recibidos":1,"aplicados":1,"rechazados":0,"importeAplicado":"100.00"}"""))

            assertEquals("100.00", linea.aplicado!!.importeAplicado.toPlainString())
            assertTrue(linea.cuadra())
        }

        @Test
        fun `un aplicado de cero que el origen SI contesto es un cero de verdad`() {
            val linea = lineaDe(recuento(), dia, Lectura.Contesto(200, """{"recibidos":0,"aplicados":0,"rechazados":0,"importe_aplicado":"0.00"}"""))

            assertEquals("0.00", linea.aplicado!!.importeAplicado.toPlainString())
            assertEquals("100.00", linea.diferencia!!.toPlainString())
        }

        @Test
        fun `sin url configurada el destino no esta configurado, y no hay cifras`() {
            val linea = lineaDe(recuento(), dia, Lectura.SinDireccion)

            assertNull(linea.aplicado)
            assertNull(linea.diferencia)
            assertTrue(linea.porQueNoSeSabe!!.startsWith("el destino rentas no está configurado"), linea.porQueNoSeSabe)
            assertTrue(linea.porQueNoSeSabe!!.contains("caja.buzon.destinos.rentas.url"), linea.porQueNoSeSabe)
        }

        @Test
        fun `si no contesta, rentas no contesto y por que`() {
            val linea = lineaDe(recuento(), dia, Lectura.NoContesta("ConnectException: Connection refused"))

            assertNull(linea.aplicado)
            assertEquals("rentas no contestó: ConnectException: Connection refused", linea.porQueNoSeSabe)
        }

        @Test
        fun `un codigo que no es 200 no contesto, con el codigo y lo que dijo`() {
            val linea = lineaDe(recuento(), dia, Lectura.Contesto(503, """{"detail":"en mantenimiento"}"""))

            assertNull(linea.aplicado)
            val motivo = linea.porQueNoSeSabe!!
            assertTrue(motivo.startsWith("rentas no contestó: "), motivo)
            assertTrue(motivo.contains("503") && motivo.contains("en mantenimiento") && motivo.contains("fecha=2026-10-02"), motivo)
        }

        @Test
        fun `una respuesta que no se puede leer no se convierte en ceros`() {
            listOf(
                "no es json" to "JSON",
                """{"recibidos":1,"aplicados":1,"rechazados":0}""" to "importe_aplicado",
                """{"aplicados":1,"rechazados":0,"importe_aplicado":"1.00"}""" to "recibidos",
                """{"recibidos":1,"aplicados":1,"rechazados":0,"importe_aplicado":100.00}""" to "cadena",
                """{"recibidos":1,"aplicados":1,"rechazados":0,"importe_aplicado":"1,00"}""" to "importe_aplicado",
                """{"recibidos":-1,"aplicados":1,"rechazados":0,"importe_aplicado":"1.00"}""" to "recibidos",
                """{"recibidos":1.5,"aplicados":1,"rechazados":0,"importe_aplicado":"1.00"}""" to "recibidos",
                """{"recibidos":"1","aplicados":1,"rechazados":0,"importe_aplicado":"1.00"}""" to "recibidos",
                """{"fecha":"2026-10-01","recibidos":1,"aplicados":1,"rechazados":0,"importe_aplicado":"1.00"}""" to "2026-10-01",
                "[]" to "JSON"
            ).forEach { (cuerpo, dice) ->
                val linea = lineaDe(recuento(), dia, Lectura.Contesto(200, cuerpo))
                assertNull(linea.aplicado, cuerpo)
                assertNull(linea.diferencia, cuerpo)
                val motivo = linea.porQueNoSeSabe!!
                assertTrue(motivo.startsWith("rentas no contestó"), motivo)
                assertTrue(motivo.contains(dice), "$cuerpo -> $motivo")
                assertFalse(linea.cuadra())
            }
        }
    }

    @Nested
    inner class DeLaPeticion {
        @Test
        fun `la fecha es obligatoria y bien escrita, si no 400 en fecha`() {
            assertEquals(LocalDate.of(2026, 10, 2), fechaDeLaConciliacion(" 2026-10-02 "))
            listOf(null, "", "  ", "02/10/2026", "2026-13-01").forEach { valor ->
                val violaciones = assertThrows<ValidationException> { fechaDeLaConciliacion(valor) }.violations
                assertEquals(listOf("fecha"), violaciones.map { it.field }, "$valor")
            }
        }
    }

    private fun recuento(
        enTransito: Int = 0,
        muertos: Int = 0,
        explicados: Int = 0
    ) = RecuentoDelDia("rentas", 1, 0, enTransito, muertos, explicados, dinero("100.00"), dinero("0.00"))

    private fun aplicado(
        importe: String,
        rechazados: Long = 0
    ) = AplicadoEnElOrigen(1 + rechazados, 1, rechazados, dinero(importe))

    private fun linea(
        recuento: RecuentoDelDia,
        aplicado: AplicadoEnElOrigen
    ) = LineaDeConciliacion(recuento, aplicado, null)

    private fun dinero(texto: String) = BigDecimal(texto)
}
