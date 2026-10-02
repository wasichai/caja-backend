package caja.turno

import caja.cobro.NORMAL
import caja.cobro.PAGO_DE_TASA
import caja.cobro.produceEvento
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

// el arqueo de un turno, sin base y sin reloj (ArqueoDelTurnoTest de caja, #36): la aritmética. que el neto sea lo
// cobrado menos lo anulado, que las partes sumen el total al céntimo, que la diferencia pueda ser negativa y que las
// anulaciones no cuenten como cobro. el privilegio, la concurrencia y el bloqueo viven en TurnoApiTest y CierreApiTest
class ArqueoDelTurnoTest {
    @Nested
    inner class DeLaSuma {
        @Test
        fun `el neto es lo cobrado menos lo anulado, y no lo anulado ignorado`() {
            val arqueo = ArqueoDelTurno.de(listOf(recibo(1, "EFECTIVO", "300.00"), recibo(2, "EFECTIVO", "120.00", "120.00")), emptyMap(), HOY)

            assertEquals("420.00", arqueo.totalCobrado.toPlainString())
            assertEquals("120.00", arqueo.totalAnulado.toPlainString())
            // el recibo anulado entró y salió: no deja nada en el cajón
            assertEquals("300.00", arqueo.neto.toPlainString())
            assertEquals(2, arqueo.recibosEmitidos)
            assertEquals(1, arqueo.recibosAnulados)
            assertEquals(HOY, arqueo.aLaFecha)
        }

        @Test
        fun `la suma de las lineas es el total, al centimo, sin redondeo de por medio`() {
            // importes que no se dividen bien a propósito: si en algún sitio hubiera una división, aquí saldría un
            // céntimo huérfano
            val arqueo =
                ArqueoDelTurno.de(
                    listOf(recibo(1, "EFECTIVO", "33.33"), recibo(2, "TARJETA", "33.33"), recibo(3, "DEPOSITO", "33.34")),
                    emptyMap(),
                    HOY
                )

            val sumaDeLasPartes = arqueo.lineas.map { it.neto }.reduce(BigDecimal::add)
            assertEquals(0, sumaDeLasPartes.compareTo(arqueo.neto))
            assertEquals("100.00", arqueo.neto.toPlainString())
        }

        @Test
        fun `las lineas salen en el orden del enumerado, no en el de llegada`() {
            val arqueo =
                ArqueoDelTurno.de(
                    listOf(recibo(1, "TRANSFERENCIA", "10.00"), recibo(2, "EFECTIVO", "20.00"), recibo(3, "DEPOSITO", "30.00")),
                    emptyMap(),
                    HOY
                )

            assertEquals(listOf("EFECTIVO", "DEPOSITO", "TRANSFERENCIA"), arqueo.lineas.map { it.formaPago })
        }

        @Test
        fun `un medio de pago sin movimiento y sin declaracion no aporta una fila`() {
            val arqueo = ArqueoDelTurno.de(listOf(recibo(1, "EFECTIVO", "50.00")), emptyMap(), HOY)

            assertEquals(1, arqueo.lineas.size)
        }

        @Test
        fun `pero un medio declarado sin movimiento si, es un descuadre que hay que ver`() {
            val arqueo =
                ArqueoDelTurno.de(
                    listOf(recibo(1, "EFECTIVO", "50.00")),
                    mapOf("EFECTIVO" to BigDecimal("50.00"), "CHEQUE" to BigDecimal("20.00")),
                    HOY
                )

            assertEquals(listOf("EFECTIVO", "CHEQUE"), arqueo.lineas.map { it.formaPago })
            // veinte soles en cheque declarados que el sistema no registró
            assertEquals("20.00", arqueo.diferencia.toPlainString())
        }

        @Test
        fun `sin recibos ni declarado, el arqueo esta vacio y en cero`() {
            val arqueo = ArqueoDelTurno.de(emptyList(), emptyMap(), HOY)

            assertEquals(emptyList<LineaDeArqueo>(), arqueo.lineas)
            assertEquals("0.00", arqueo.neto.toPlainString())
            assertEquals("0.00", arqueo.diferencia.toPlainString())
            assertTrue(arqueo.cuadra())
        }
    }

    @Nested
    inner class DeLaDiferencia {
        @Test
        fun `si falta dinero en el cajon, la diferencia sale negativa y el arqueo existe`() {
            val arqueo = ArqueoDelTurno.de(listOf(recibo(1, "EFECTIVO", "500.00")), mapOf("EFECTIVO" to BigDecimal("490.00")), HOY)

            assertEquals("-10.00", arqueo.diferencia.toPlainString())
            assertEquals(
                "-10.00",
                arqueo.lineas
                    .single()
                    .diferencia
                    .toPlainString()
            )
            assertFalse(arqueo.cuadra())
        }

        @Test
        fun `y si cuadra, la diferencia es cero`() {
            val arqueo = ArqueoDelTurno.de(listOf(recibo(1, "EFECTIVO", "500.00", "100.00")), mapOf("EFECTIVO" to BigDecimal("400.00")), HOY)

            assertEquals("400.00", arqueo.neto.toPlainString())
            assertEquals(0, arqueo.diferencia.signum())
            assertTrue(arqueo.cuadra())
        }

        @Test
        fun `lo que no se declara cuenta como cero`() {
            val arqueo = ArqueoDelTurno.de(listOf(recibo(1, "EFECTIVO", "80.00"), recibo(2, "TARJETA", "20.00")), mapOf("EFECTIVO" to BigDecimal("80.00")), HOY)

            val tarjeta = arqueo.lineas.single { it.formaPago == "TARJETA" }
            assertEquals("0.00", tarjeta.declarado.toPlainString())
            assertEquals("-20.00", tarjeta.diferencia.toPlainString())
            assertEquals("80.00", arqueo.totalDeclarado.toPlainString())
            assertEquals("-20.00", arqueo.diferencia.toPlainString())
        }
    }

    @Nested
    inner class DeLoImposible {
        @Test
        fun `una anulacion no puede sacar del cajon mas de lo que entro por ese medio`() {
            val error =
                assertThrows<IllegalArgumentException> {
                    LineaDeArqueo("EFECTIVO", BigDecimal("100.00"), BigDecimal("150.00"), BigDecimal.ZERO)
                }
            assertTrue(error.message!!.contains("no puede sacar del cajón más de lo que entró"), error.message)
        }

        @Test
        fun `un recibo no puede tener anulado mas que su total`() {
            val error = assertThrows<IllegalArgumentException> { recibo(1, "EFECTIVO", "100.00", "101.00") }
            assertTrue(error.message!!.contains("congela el total del recibo"), error.message)
        }

        @Test
        fun `no se declara en negativo, eso no es contar, es inventar`() {
            assertThrows<IllegalArgumentException> { LineaDeArqueo("EFECTIVO", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("-1.00")) }
            assertThrows<IllegalArgumentException> {
                ArqueoDelTurno.de(listOf(recibo(1, "EFECTIVO", "10.00")), mapOf("EFECTIVO" to BigDecimal("-1.00")), HOY)
            }
        }

        @Test
        fun `ni se declara ni se cobra con una forma de pago que no existe`() {
            assertThrows<IllegalArgumentException> { ArqueoDelTurno.de(emptyList(), mapOf("BITCOIN" to BigDecimal("1.00")), HOY) }
            assertThrows<IllegalArgumentException> { recibo(1, "BITCOIN", "1.00") }
        }

        @Test
        fun `un recibo no cobra ni devuelve en negativo`() {
            assertThrows<IllegalArgumentException> { recibo(1, "EFECTIVO", "-1.00") }
            assertThrows<IllegalArgumentException> { recibo(1, "EFECTIVO", "1.00", "-1.00") }
        }
    }

    // un recibo roto (escrito por la API genérica o en la base, nunca por la cobranza) no tumba el arqueo entero ni
    // bloquea el cierre: queda fuera de las cifras y se nombra con su porqué. el invariante de ReciboDelTurno no cambia
    @Nested
    inner class DeLosRecibosRotos {
        @Test
        fun `un recibo roto queda fuera del arqueo y se nombra con su porque, y los demas se cuentan`() {
            val recibos =
                RecibosDelTurno.de(
                    listOf(
                        fila(1, "EFECTIVO", "100.00"),
                        fila(2, "EFECTIVO", "-50.00"),
                        fila(3, "TARJETA", "100.00", "120.00"),
                        fila(4, "EFECTIVO", "30.00", "-30.00"),
                        fila(5, "BITCOIN", "10.00")
                    )
                )

            assertEquals(listOf("001-0000001"), recibos.contables.map { it.numero })
            assertEquals(listOf("001-0000002", "001-0000003", "001-0000004", "001-0000005"), recibos.rotos.map { it.numero })
            assertTrue(recibos.rotos[0].motivo.contains("-50.00"), recibos.rotos[0].motivo)
            assertTrue(recibos.rotos[1].motivo.contains("congela el total del recibo"), recibos.rotos[1].motivo)
            assertTrue(recibos.rotos[3].motivo.contains("BITCOIN"), recibos.rotos[3].motivo)
            val arqueo = ArqueoDelTurno.de(recibos.contables, emptyMap(), HOY)
            assertEquals("100.00", arqueo.neto.toPlainString())
            assertEquals(1, arqueo.recibosEmitidos)
        }

        @Test
        fun `el defecto de un recibo es el mismo invariante de ReciboDelTurno`() {
            assertNull(defectoDelRecibo("EFECTIVO", BigDecimal("10.00"), BigDecimal("10.00")))
            assertNull(defectoDelRecibo("EFECTIVO", BigDecimal("0.00"), BigDecimal("0.00")))
            val defecto = defectoDelRecibo("EFECTIVO", BigDecimal("-1.00"), BigDecimal.ZERO)!!
            val error = assertThrows<IllegalArgumentException> { recibo(9, "EFECTIVO", "-1.00") }
            assertTrue(error.message!!.contains(defecto), error.message)
        }

        @Test
        fun `sin recibos rotos no hay nada que nombrar`() {
            val recibos = RecibosDelTurno.de(listOf(fila(1, "EFECTIVO", "10.00", "10.00")))
            assertEquals(1, recibos.contables.size)
            assertTrue(recibos.rotos.isEmpty())
        }

        private fun fila(
            numero: Long,
            forma: String,
            total: String,
            anulado: String = "0.00"
        ) = FilaDeRecibo("001-${"%07d".format(numero)}", NORMAL, forma, BigDecimal(total), BigDecimal(anulado))
    }

    // que produce evento y que no (#118 de caja): el cuadre parte lo recaudado en dos mitades, y las dos suman el neto
    @Nested
    inner class DelCuadre {
        @Test
        fun `solo una cobranza normal produce evento, es lo unico que se encola`() {
            assertTrue(produceEvento(NORMAL))
        }

        @Test
        fun `una tasa no tiene a quien avisarle, no vino de ninguna orden`() {
            assertFalse(produceEvento(PAGO_DE_TASA))
        }

        @Test
        fun `las dos mitades suman el neto, y lo anulado se resta de su lado`() {
            val recibos =
                listOf(
                    recibo(1, "EFECTIVO", "150.50"),
                    recibo(2, "TARJETA", "80.00", "80.00"),
                    recibo(3, "EFECTIVO", "36.90", tipoPago = PAGO_DE_TASA),
                    recibo(4, "EFECTIVO", "12.30", "12.30", tipoPago = PAGO_DE_TASA)
                )
            val arqueo = ArqueoDelTurno.de(recibos, emptyMap(), HOY)
            val cuadre = Cuadre.de(recibos)

            assertEquals("150.50", cuadre.conEvento.toPlainString())
            assertEquals("36.90", cuadre.sinEvento.toPlainString())
            assertEquals(0, cuadre.total.compareTo(arqueo.neto))
            assertTrue(cuadre.sumaElNetoDe(arqueo))
        }

        @Test
        fun `un cuadre que no suma el neto lo dice`() {
            val arqueo = ArqueoDelTurno.de(listOf(recibo(1, "EFECTIVO", "10.00")), emptyMap(), HOY)

            assertFalse(Cuadre(BigDecimal("9.99"), BigDecimal("0.00")).sumaElNetoDe(arqueo))
        }
    }

    @Nested
    inner class DelEstado {
        @Test
        fun `sin movimientos, abierto`() {
            assertEquals(EstadoDelTurno.ABIERTO, EstadoDelTurno.de(emptyList()))
        }

        @Test
        fun `tras un cierre, cerrado, y tras su reversion, abierto otra vez`() {
            val cierre = Movimiento("c1", TipoDeMovimiento.CIERRE, 1)
            assertEquals(EstadoDelTurno.CERRADO, EstadoDelTurno.de(listOf(cierre)))
            // reversar reabre: es la única forma de seguir cobrando ese día
            assertEquals(EstadoDelTurno.ABIERTO, EstadoDelTurno.de(listOf(cierre, Movimiento("r1", TipoDeMovimiento.REVERSION, 2))))
            assertNull(cierreVigente(emptyList()))
        }
    }

    private companion object {
        val HOY: LocalDate = LocalDate.of(2026, 3, 15)

        fun recibo(
            numero: Long,
            forma: String,
            total: String,
            anulado: String = "0.00",
            tipoPago: String = NORMAL
        ) = ReciboDelTurno("001-${"%07d".format(numero)}", tipoPago, forma, BigDecimal(total), BigDecimal(anulado))
    }
}
