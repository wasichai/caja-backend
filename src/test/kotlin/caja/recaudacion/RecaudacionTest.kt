package caja.recaudacion

import caja.cobro.NORMAL
import caja.cobro.PAGO_DE_TASA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// las agregaciones puras de la recaudación (ConsultaDeRecaudacion, RecaudacionDeTributo y RecaudacionDePartida de caja,
// #36, RF-088, RF-089): por origen y por área y partida, lo anulado que se resta, las partes que suman el total al
// céntimo, el hueco de la partida que se dice, y lo que llega en la petición
class RecaudacionTest {
    @Nested
    inner class DelOrigen {
        @Test
        fun `un recibo de tasas es del origen TASA, tengan lo que tengan sus lineas`() {
            assertEquals("TASA", origenDelRecibo(PAGO_DE_TASA, listOf("rentas")))
            assertEquals("TASA", origenDelRecibo(PAGO_DE_TASA, emptyList()))
        }

        @Test
        fun `un recibo de ordenes es del sistema de origen de sus lineas`() {
            assertEquals("rentas", origenDelRecibo(NORMAL, listOf("rentas", "rentas")))
        }

        @Test
        fun `sin lineas del cobro, o con dos sistemas, el origen no se sabe y no se inventa`() {
            assertNull(origenDelRecibo(NORMAL, emptyList()))
            assertNull(origenDelRecibo(NORMAL, listOf(null)))
            assertNull(origenDelRecibo(NORMAL, listOf("rentas", "mercados")))
        }

        @Test
        fun `solo cuentan las lineas con el sello de la transaccion del recibo`() {
            val cobro = Instant.parse("2026-10-02T15:00:00.123456Z")
            val lineas = listOf(Sellada("de la cobranza", cobro), Sellada("forjada después", cobro.plusSeconds(60)))

            assertEquals(listOf("de la cobranza"), delCobro(cobro, lineas) { it.creadoEn }.map { it.que })
        }
    }

    @Nested
    inner class DelAvance {
        @Test
        fun `una fila por origen, y un recibo anulado resta su importe anulado en su origen`() {
            val avance =
                Avance.de(
                    listOf(
                        ReciboRecaudado("rentas", dinero("150.50"), dinero("0")),
                        ReciboRecaudado("rentas", dinero("220.00"), dinero("220.00")),
                        ReciboRecaudado("TASA", dinero("36.90"), dinero("0"))
                    )
                )

            assertEquals(
                listOf("rentas 370.50 220.00 150.50", "TASA 36.90 0.00 36.90"),
                avance.filas.map { "${it.origen} ${it.cobrado.toPlainString()} ${it.anulado.toPlainString()} ${it.neto.toPlainString()}" }
            )
            assertEquals("407.40", avance.cobrado.toPlainString())
            assertEquals("220.00", avance.anulado.toPlainString())
            assertEquals("187.40", avance.neto.toPlainString())
        }

        @Test
        fun `las filas suman el total al centimo, sin redondeo de por medio`() {
            val avance =
                Avance.de(
                    listOf(
                        ReciboRecaudado("rentas", dinero("100.00"), dinero("0")),
                        ReciboRecaudado("TASA", dinero("33.33"), dinero("0")),
                        ReciboRecaudado("TASA", dinero("33.34"), dinero("0")),
                        ReciboRecaudado("mercados", dinero("0.01"), dinero("0.01"))
                    )
                )

            assertEquals(
                0,
                avance.filas
                    .map { it.neto }
                    .reduce(BigDecimal::add)
                    .compareTo(avance.neto)
            )
            assertEquals("166.67", avance.neto.toPlainString())
        }

        @Test
        fun `las filas van de mayor a menor cobrado y, empatadas, por origen, y sin origen al final del empate`() {
            val avance =
                Avance.de(
                    listOf(
                        ReciboRecaudado("b", dinero("10.00"), dinero("0")),
                        ReciboRecaudado(null, dinero("10.00"), dinero("0")),
                        ReciboRecaudado("a", dinero("10.00"), dinero("0")),
                        ReciboRecaudado("c", dinero("99.00"), dinero("0"))
                    )
                )

            assertEquals(listOf("c", "a", "b", null), avance.filas.map { it.origen })
        }

        @Test
        fun `sin recibos no hay filas, y el total es cero porque de verdad no se cobro nada`() {
            val avance = Avance.de(emptyList())

            assertTrue(avance.filas.isEmpty())
            assertEquals("0.00", avance.neto.toPlainString())
        }

        @Test
        fun `lo imposible lanza en vez de contarse`() {
            assertThrows<IllegalArgumentException> { ReciboRecaudado("rentas", dinero("10.00"), dinero("10.01")) }
            assertThrows<IllegalArgumentException> { ReciboRecaudado("rentas", dinero("-1.00"), dinero("0")) }
        }

        // lo imposible no entra en las cifras, pero tampoco tumba el reporte de todo un año: el recibo roto se dice
        @Test
        fun `un recibo roto se dice con su porque, y el que esta bien no`() {
            assertNull(defectoDeRecaudacion(dinero("100.00"), dinero("0.00"), listOf(dinero("60.00"), dinero("40.00"))))
            assertNull(defectoDeRecaudacion(dinero("12.30"), dinero("12.30"), emptyList()))

            val negativo = defectoDeRecaudacion(dinero("-20.00"), dinero("0.00"), emptyList())
            assertTrue(negativo != null && negativo.contains("-20.00"), negativo)
            assertTrue(defectoDeRecaudacion(dinero("10.00"), dinero("10.01"), emptyList()) != null)
            val linea = defectoDeRecaudacion(dinero("100.00"), dinero("0.00"), listOf(dinero("150.00"), dinero("-50.00")))
            assertTrue(linea != null && linea.contains("-50.00"), linea)
            assertTrue(defectoDeRecaudacion(dinero("100.00"), dinero("0.00"), listOf(null)) != null)
        }
    }

    @Nested
    inner class DeLaDistribucion {
        @Test
        fun `las tasas traen su area y su partida, lo de una orden ninguna, y va a neto_sin_partida`() {
            val distribucion =
                Distribucion.de(
                    listOf(
                        LineaRecaudada("A-1", "RENTAS", "1.3.1", "T-001", dinero("36.90"), anulada = false),
                        LineaRecaudada(null, null, null, "rentas", dinero("400.00"), anulada = false)
                    )
                )

            val conPartida = distribucion.filas.single { it.tienePartida }
            assertEquals("A-1 RENTAS 1.3.1 T-001", "${conPartida.area} ${conPartida.areaNombre} ${conPartida.partida} ${conPartida.concepto}")
            val sinPartida = distribucion.filas.single { !it.tienePartida }
            assertNull(sinPartida.area)
            assertNull(sinPartida.partida)
            assertEquals("rentas", sinPartida.concepto)
            assertEquals("436.90", distribucion.neto.toPlainString())
            assertEquals("400.00", distribucion.netoSinPartida.toPlainString())
        }

        @Test
        fun `las partes suman el total, y una anulacion se resta linea por linea`() {
            val distribucion =
                Distribucion.de(
                    listOf(
                        LineaRecaudada("A-1", "RENTAS", "1.3.1", "T-001", dinero("33.33"), anulada = false),
                        LineaRecaudada("A-1", "RENTAS", "1.3.1", "T-001", dinero("33.33"), anulada = true),
                        LineaRecaudada("A-2", "MERCADOS", "1.3.2", "T-002", dinero("33.34"), anulada = false),
                        LineaRecaudada(null, null, null, "rentas", dinero("100.00"), anulada = false)
                    )
                )

            val t001 = distribucion.filas.single { it.concepto == "T-001" }
            assertEquals("66.66 33.33 33.33", "${t001.cobrado.toPlainString()} ${t001.anulado.toPlainString()} ${t001.neto.toPlainString()}")
            assertEquals(
                0,
                distribucion.filas
                    .map { it.neto }
                    .reduce(BigDecimal::add)
                    .compareTo(distribucion.neto)
            )
            assertEquals("166.67", distribucion.neto.toPlainString())
            assertEquals("100.00", distribucion.netoSinPartida.toPlainString())
        }

        @Test
        fun `una fila por area, partida y concepto, de mayor a menor cobrado`() {
            val distribucion =
                Distribucion.de(
                    listOf(
                        LineaRecaudada("A-1", "RENTAS", "1.3.1", "T-001", dinero("10.00"), anulada = false),
                        LineaRecaudada("A-1", "RENTAS", "1.3.1", "T-002", dinero("20.00"), anulada = false),
                        LineaRecaudada("A-1", "RENTAS", "1.3.1", "T-001", dinero("15.00"), anulada = false)
                    )
                )

            assertEquals(listOf("T-001 25.00", "T-002 20.00"), distribucion.filas.map { "${it.concepto} ${it.cobrado.toPlainString()}" })
        }

        @Test
        fun `sin lineas el neto y el neto sin partida son cero, y no falta ningun dato`() {
            val distribucion = Distribucion.de(emptyList())

            assertEquals("0.00", distribucion.neto.toPlainString())
            assertEquals("0.00", distribucion.netoSinPartida.toPlainString())
        }
    }

    @Nested
    inner class DeLaPeticion {
        private val hoy = LocalDate.of(2026, 10, 2)

        @Test
        fun `el rango va por dias, los dos incluidos, y sin hasta llega hasta hoy desde el primero de su anio`() {
            assertEquals(Rango(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)), rangoPedido("2026-03-01", "2026-03-31", hoy))
            assertEquals(Rango(LocalDate.of(2026, 1, 1), hoy), rangoPedido(null, null, hoy))
            assertEquals(Rango(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)), rangoPedido(" ", "2025-06-30", hoy))
            assertEquals(Rango(LocalDate.of(2026, 9, 1), hoy), rangoPedido("2026-09-01", null, hoy))
        }

        @Test
        fun `un rango al reves, o una fecha mal escrita, es 400 en su campo`() {
            assertEquals(listOf("hasta"), campos { rangoPedido("2026-03-31", "2026-03-01", hoy) })
            assertEquals(listOf("desde"), campos { rangoPedido("01/03/2026", "2026-03-31", hoy) })
            assertEquals(listOf("desde", "hasta"), campos { rangoPedido("ayer", "hoy", hoy) })
        }

        @Test
        fun `el area admite el codigo o la etiqueta COD — nombre, y se queda con el codigo`() {
            assertEquals("A-113300", codigoDeArea("A-113300"))
            assertEquals("113300", codigoDeArea(" 113300 — SUBGERENCIA DE COMERCIALIZACIÓN "))
            assertNull(codigoDeArea("  "))
            assertNull(codigoDeArea(null))
        }

        @Test
        fun `el origen se compara sin mirar mayusculas`() {
            assertTrue(esDelOrigen("rentas", " RENTAS "))
            assertTrue(esDelOrigen("TASA", "tasa"))
            assertFalse(esDelOrigen("rentas", "mercados"))
            assertFalse(esDelOrigen(null, "rentas"))
            assertTrue(esDelOrigen(null, null))
        }
    }

    private class Sellada(
        val que: String,
        val creadoEn: Instant?
    )

    private fun campos(regla: () -> Unit): List<String> = assertThrows<ValidationException> { regla() }.violations.map { it.field }

    private fun dinero(texto: String) = BigDecimal(texto)
}
