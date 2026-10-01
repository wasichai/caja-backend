package caja.cobro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// las reglas puras de la caja de tasas (TasaTest y CobrarTasasEnVentanillaTest de caja): la vigencia con sus extremos,
// la tarifa vigente a una fecha, la multiplicación exacta y lo que llega en la petición. la fecha entra como argumento
// (regla 6) y ninguna cifra es de una tarifa real: son de la prueba
class TasasTest {
    private val enero = LocalDate.of(2026, 1, 1)
    private val junio = LocalDate.of(2026, 6, 30)
    private val julio = LocalDate.of(2026, 7, 1)

    // la vigencia: Tasa.vigenteA de caja

    @Test
    fun `la vigencia incluye sus extremos`() {
        val tarifa = tasa("12.50", enero, junio)

        assertTrue(tarifa.vigenteA(enero))
        assertTrue(tarifa.vigenteA(junio))
        assertFalse(tarifa.vigenteA(julio))
        assertFalse(tarifa.vigenteA(enero.minusDays(1)))
    }

    @Test
    fun `una tarifa abierta no caduca`() {
        assertTrue(tasa("12.50", enero, null).vigenteA(LocalDate.of(2030, 12, 31)))
    }

    @Test
    fun `la vigencia no puede ir al reves`() {
        val error = assertThrows<ConflictException> { tasa("1.00", julio, enero).vigenteA(junio) }
        assertTrue(error.message.contains("termina antes de empezar"), error.message)
    }

    // la tarifa vigente: TasaRepositoryJdbc.vigenteA de caja

    @Test
    fun `la tarifa vigente es la que ya empezo y no termino`() {
        val vieja = tasa("10.00", enero, junio)
        val nueva = tasa("12.30", julio, null)

        assertEquals(vieja, tarifaVigente(listOf(vieja, nueva), junio))
        assertEquals(nueva, tarifaVigente(listOf(vieja, nueva), julio))
        assertNull(tarifaVigente(listOf(vieja, nueva), enero.minusDays(1)))
        assertNull(tarifaVigente(emptyList(), julio))
    }

    @Test
    fun `si dos vigencias se solapan, rige la que empezo despues`() {
        val abierta = tasa("10.00", enero, null)
        val ordenanza = tasa("12.30", julio, null)

        assertEquals(ordenanza, tarifaVigente(listOf(ordenanza, abierta), julio))
        assertEquals(ordenanza, tarifaVigente(listOf(abierta, ordenanza), julio))
    }

    // el monto de la línea: precio × cantidad, sin redondear (recibo_detalle_tasa_ck de caja)

    @Test
    fun `se multiplica sin perder centimos`() {
        assertEquals(BigDecimal("36.90"), montoDeLinea(BigDecimal("12.30"), 3))
        assertEquals(BigDecimal("0.70"), montoDeLinea(BigDecimal("0.10"), 7))
        assertEquals(BigDecimal("37.50"), montoDeLinea(BigDecimal("12.50"), 3))
        assertThrows<IllegalArgumentException> { montoDeLinea(BigDecimal("12.30"), 0) }
    }

    // cotizar: cada concepto con la tarifa vigente a la fecha, o lo que impide cobrarlo

    @Test
    fun `cotizar da cada linea con su precio vigente y su monto`() {
        val t1 = tasa("12.30", enero, null, codigo = "T-1")
        val t2 = tasa("0.10", enero, null, codigo = "T-2")

        val cotizacion = cotizar(listOf(LineaDeTasaPedida("T-1", 3), LineaDeTasaPedida("T-2", 7)), mapOf("T-1" to listOf(t1), "T-2" to listOf(t2)), julio)

        assertTrue(cotizacion.impedimentos.isEmpty())
        assertEquals(listOf(BigDecimal("36.90"), BigDecimal("0.70")), cotizacion.lineas.map { it.monto })
        assertEquals(BigDecimal("37.60"), totalDe(cotizacion.lineas.map { it.monto }))
    }

    @Test
    fun `un concepto sin tarifa vigente es un 404 con su codigo`() {
        val futura = tasa("12.30", julio, null, codigo = "T-1")

        val cotizacion = cotizar(listOf(LineaDeTasaPedida("T-1", 1), LineaDeTasaPedida("T-9", 1)), mapOf("T-1" to listOf(futura)), junio)

        assertTrue(cotizacion.lineas.isEmpty())
        assertEquals(2, cotizacion.impedimentos.size)
        assertTrue(cotizacion.impedimentos.all { it is NotFoundException })
        assertTrue(cotizacion.impedimentos[0].message.contains("'T-1'"), cotizacion.impedimentos[0].message)
        assertTrue(cotizacion.impedimentos[1].message.contains("'T-9'"), cotizacion.impedimentos[1].message)
    }

    @Test
    fun `una tarifa en cero es un 409, no un recibo por cero`() {
        val cero = tasa("0.00", enero, null, codigo = "T-0")

        val cotizacion = cotizar(listOf(LineaDeTasaPedida("T-0", 2)), mapOf("T-0" to listOf(cero)), julio)

        assertTrue(cotizacion.lineas.isEmpty())
        val error = cotizacion.impedimentos.single()
        assertTrue(error is ConflictException && error.message.contains("tarifa en cero"), error.message)
    }

    @Test
    fun `la linea del recibo lleva la tasa, su descripcion, la cantidad, el precio y el monto`() {
        val t = tasa("12.30", enero, null, codigo = "T-1").copy(id = "id-de-la-tasa", descripcion = "CONSTANCIA")

        val linea = lineaDeTasa(cotizar(listOf(LineaDeTasaPedida("T-1", 3)), mapOf("T-1" to listOf(t)), julio).lineas.single())

        assertEquals("id-de-la-tasa", linea.tasa)
        assertEquals("CONSTANCIA", linea.concepto)
        assertEquals(3L, linea.cantidad)
        assertEquals(BigDecimal("12.30"), linea.precioUnitario)
        assertEquals(BigDecimal("36.90"), linea.monto)
        assertNull(linea.orden)
        assertNull(linea.detalle)
    }

    // una tasa no produce evento: no vino de una orden (TipoDePago.produceEvento de caja)

    @Test
    fun `solo un cobro de ordenes produce evento`() {
        assertTrue(produceEvento(NORMAL))
        assertFalse(produceEvento(PAGO_DE_TASA))
    }

    // lo que llega en la petición

    @Test
    fun `la cantidad es 1 si no viene, y al menos 1`() {
        assertEquals(1, cantidadPedida(null, "cantidad"))
        assertEquals(1, cantidadPedida(" ", "cantidad"))
        assertEquals(3, cantidadPedida("3", "cantidad"))
        listOf("0", "-1", "1.5", "tres", "99999999999").forEach {
            val error = assertThrows<ValidationException>(it) { cantidadPedida(it, "conceptos[0].cantidad") }
            assertEquals("conceptos[0].cantidad", error.violations.single().field)
        }
    }

    @Test
    fun `sin conceptos no se cobra`() {
        listOf(null, emptyList<ConceptoPedido>()).forEach {
            val error = assertThrows<ValidationException> { conceptosPedidos(it) }
            assertEquals("conceptos", error.violations.single().field)
        }
    }

    @Test
    fun `cada concepto lleva su codigo, y su cantidad o 1`() {
        assertEquals(
            listOf(LineaDeTasaPedida("T-1", 1), LineaDeTasaPedida("T-2", 4)),
            conceptosPedidos(listOf(ConceptoPedido(" T-1 "), ConceptoPedido("T-2", "4")))
        )
        val sinCodigo = assertThrows<ValidationException> { conceptosPedidos(listOf(ConceptoPedido("T-1"), ConceptoPedido(" "))) }
        assertEquals("conceptos[1].codigo", sinCodigo.violations.single().field)
        val enCero = assertThrows<ValidationException> { conceptosPedidos(listOf(ConceptoPedido("T-1", "0"))) }
        assertEquals("conceptos[0].cantidad", enCero.violations.single().field)
    }

    @Test
    fun `un precio o un importe en la peticion es un 400 que lo nombra`() {
        val enElConcepto = assertThrows<ValidationException> { conceptosPedidos(listOf(ConceptoPedido("T-1").apply { desconocido("precio", "1.00") })) }
        assertEquals("conceptos[0].precio", enElConcepto.violations.single().field)
        assertTrue(
            enElConcepto.violations
                .single()
                .message
                .contains("tarifa vigente"),
            enElConcepto.violations.single().message
        )

        val enElCuerpo = assertThrows<ValidationException> { sinPrecioNiCamposDesconocidos(listOf("importe")) }
        assertEquals("importe", enElCuerpo.violations.single().field)
        assertTrue(
            enElCuerpo.violations
                .single()
                .message
                .contains("tarifa vigente")
        )
        val otro = assertThrows<ValidationException> { sinPrecioNiCamposDesconocidos(listOf("tributo")) }
        assertEquals("tributo", otro.violations.single().field)
        sinPrecioNiCamposDesconocidos(emptyList())
    }

    private fun tasa(
        importe: String,
        desde: LocalDate,
        hasta: LocalDate?,
        codigo: String = "T-001"
    ) = Tasa(
        id = "tasa-$codigo-$desde",
        codigo = codigo,
        descripcion = "CONCEPTO DEL TUPA DE LA PRUEBA",
        partidaPresupuestal = "1.3.1.1.1.1",
        importe = BigDecimal(importe),
        vigenciaDesde = desde,
        vigenciaHasta = hasta,
        documentoFuente = "ORDENANZA DE LA PRUEBA"
    )
}
