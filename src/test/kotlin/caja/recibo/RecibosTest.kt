package caja.recibo

import caja.comun.diaPedido
import caja.comun.rangoDeDias
import caja.comun.sinCamposDesconocidos
import caja.modelo.LineaRecibo
import caja.modelo.PAGO_ANULADO
import caja.modelo.Recibo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ForbiddenException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// las reglas puras del recibo después de emitido (AnularRecibo, DuplicadoDeRecibo, MovimientoDeRecibo y
// CriterioDeRecibos de caja): el resumen del duplicado, lo que llega en la anulación, el número del papel, los
// filtros del listado, el mismo día, el recibo ajeno y el cuerpo de PAGO_ANULADO
class RecibosTest {
    private val hoy = LocalDate.of(2026, 10, 2)

    // el resumen del duplicado: SHA-256 de lo congelado, no de los bytes del pdf

    @Test
    fun `el mismo recibo da el mismo resumen, en hexadecimal de 64`() {
        val resumen = resumenDelRecibo(recibo(), lineas())

        assertEquals(64, resumen.length)
        assertTrue(resumen.all { it in '0'..'9' || it in 'a'..'f' }, resumen)
        assertEquals(resumen, resumenDelRecibo(recibo(), lineas()))
        // el orden en que llegan las líneas no lo cambia: se dibujan en un orden fijo
        assertEquals(resumen, resumenDelRecibo(recibo(), lineas().reversed()))
    }

    @Test
    fun `una cifra distinta da otro resumen`() {
        val resumen = resumenDelRecibo(recibo(), lineas())

        assertNotEquals(resumen, resumenDelRecibo(recibo().copy(total = BigDecimal("150.51")), lineas()))
        assertNotEquals(resumen, resumenDelRecibo(recibo(), lineas().map { if (it.orden == "o-1") it.copy(monto = BigDecimal("100.01")) else it }))
        assertNotEquals(resumen, resumenDelRecibo(recibo().copy(actualizadoA = hoy.plusDays(1)), lineas()))
        assertNotEquals(resumen, resumenDelRecibo(recibo().copy(pagadorNombre = "OTRO"), lineas()))
    }

    // lo que llega en la anulación: los largos de recibo_movimiento de caja

    @Test
    fun `el motivo es obligatorio, no en blanco y de hasta 80`() {
        assertEquals("DOBLE COBRO", motivoDeAnulacion("  DOBLE COBRO "))
        assertEquals("x".repeat(80), motivoDeAnulacion("x".repeat(80)))
        listOf(null, "", "   ", "x".repeat(81)).forEach {
            val error = assertThrows<ValidationException>(it.toString()) { motivoDeAnulacion(it) }
            assertEquals("motivo", error.violations.single().field)
        }
    }

    @Test
    fun `quien autoriza va hasta 80 y el memorando hasta 40, los dos opcionales`() {
        assertNull(autorizadoPor(null))
        assertNull(autorizadoPor("  "))
        assertEquals("JEFE DE CAJA", autorizadoPor(" JEFE DE CAJA "))
        assertEquals("x".repeat(80), autorizadoPor("x".repeat(80)))
        assertEquals("autorizado_por", assertThrows<ValidationException> { autorizadoPor("x".repeat(81)) }.violations.single().field)

        assertNull(documentoDeAutorizacion(""))
        assertEquals("MEMO 12-2026", documentoDeAutorizacion("MEMO 12-2026"))
        assertEquals("x".repeat(40), documentoDeAutorizacion("x".repeat(40)))
        assertEquals(
            "documento_autorizacion",
            assertThrows<ValidationException> { documentoDeAutorizacion("x".repeat(41)) }.violations.single().field
        )
    }

    @Test
    fun `una clave desconocida en el cuerpo es un 400 que las nombra todas`() {
        val error = assertThrows<ValidationException> { sinCamposDesconocidos(listOf("estado", "importe"), "una anulación") }
        assertEquals(listOf("estado", "importe"), error.violations.map { it.field })
        sinCamposDesconocidos(emptyList(), "una anulación")
    }

    // el número del papel

    @Test
    fun `el numero va como en el papel, y uno mal formado es un 400`() {
        assertEquals("001-0000123", numeroDeRecibo(" 001-0000123 "))
        assertEquals("AB1-0000007", numeroDeRecibo("ab1-7"))
        assertEquals("A-12345678", numeroDeRecibo("A-12345678"))
        listOf(null, "", "abc", "001-", "-0000001", "001-00x1", "ABCDEF-0000001", "001-0000000", "0 1-0000001").forEach {
            val error = assertThrows<ValidationException>(it.toString()) { numeroDeRecibo(it) }
            assertEquals("numero_impreso", error.violations.single().field)
        }
    }

    // la página del listado dentro de su tramo desempatado

    @Test
    fun `la pagina sale del tramo en su desplazamiento, y una lectura descuadrada no revienta`() {
        val tramo = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c"), paginaDelTramo(tramo, 3, 2, 2))
        assertEquals(listOf("a", "b"), paginaDelTramo(tramo, 0, 0, 2))
        // la carrera de la revisión: en la página 0, un cobro confirmado entre la lectura de la página y la cuenta de
        // los más recientes deja masRecientes = 1 > desplazamiento = 0. drop(-1) lanzaba IllegalArgumentException (500)
        assertEquals(listOf("a", "b"), paginaDelTramo(tramo, 0, 1, 2))
    }

    // los filtros del listado

    @Test
    fun `el estado es EMITIDO o ANULADO, o ninguno, y otro es un 400`() {
        assertNull(estadoPedido(null))
        assertNull(estadoPedido(" "))
        assertEquals(ANULADO, estadoPedido(" anulado "))
        assertEquals(EMITIDO, estadoPedido("EMITIDO"))
        assertEquals("estado", assertThrows<ValidationException> { estadoPedido("Pagado") }.violations.single().field)
        assertEquals(ANULADO, estadoDelRecibo(anulado = true))
        assertEquals(EMITIDO, estadoDelRecibo(anulado = false))
    }

    @Test
    fun `los dias del rango son fechas, y al reves es un 400`() {
        assertNull(diaPedido(null, "desde"))
        assertEquals(hoy, diaPedido(" 2026-10-02 ", "desde"))
        assertEquals("hasta", assertThrows<ValidationException> { diaPedido("02/10/2026", "hasta") }.violations.single().field)

        rangoDeDias(hoy, hoy)
        rangoDeDias(null, hoy)
        rangoDeDias(hoy, null)
        val alReves = assertThrows<ValidationException> { rangoDeDias(hoy, hoy.minusDays(1)) }
        assertEquals("hasta", alReves.violations.single().field)
    }

    // la anulación: solo el mismo día, y el recibo ajeno exige ANULAR_AJENO

    @Test
    fun `un recibo de otro dia no se anula, y es un 422`() {
        delMismoDia("001-0000001", hoy, hoy)
        val error = assertThrows<FueraDelDiaDePago> { delMismoDia("001-0000001", hoy.minusDays(1), hoy) }
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status)
        assertTrue(error.message.contains("devolución"), error.message)
    }

    @Test
    fun `el recibo de otro cajero lo anula quien tiene ANULAR_AJENO`() {
        // el propio se anula sin la acción; el ajeno, solo con ella (quien llama la calcula con el objectId del recibo)
        puedeAnular("ana@muni.test", usuario("ana@muni.test", "CAJERO"), "001-0000001", false)
        puedeAnular("ana@muni.test", usuario("jefe@muni.test", "CUALQUIERA"), "001-0000001", true)
        val error = assertThrows<ForbiddenException> { puedeAnular("ana@muni.test", usuario("luis@muni.test", "OTRO"), "001-0000001", false) }
        assertTrue(error.message.contains(ANULAR_AJENO), error.message)
    }

    // el cuerpo de PAGO_ANULADO (ComponedorDeEventosJson.pagoAnulado de caja)

    @Test
    fun `el cuerpo de PAGO_ANULADO nombra el pago que deshace`() {
        val pagoId = UUID.randomUUID()
        val original = UUID.randomUUID().toString()

        val cuerpo = JsonMapper.builder().build().readTree(cuerpoPagoAnulado(pagoId, original, recibo(), "DOBLE COBRO", hoy))

        assertEquals(listOf("pagoId", "tipo", "pagoOriginalId", "recibo", "motivo", "fecha", "total"), cuerpo.propertyNames().toList())
        assertEquals(pagoId.toString(), cuerpo["pagoId"].asString())
        assertEquals(PAGO_ANULADO, cuerpo["tipo"].asString())
        assertEquals(original, cuerpo["pagoOriginalId"].asString())
        assertEquals(listOf("numero", "serie", "fechaDePago", "cajero", "formaDePago"), cuerpo["recibo"].propertyNames().toList())
        assertEquals("001-0000001", cuerpo["recibo"]["numero"].asString())
        assertEquals("DOBLE COBRO", cuerpo["motivo"].asString())
        assertEquals("2026-10-02", cuerpo["fecha"].asString())
        assertEquals("150.50", cuerpo["total"].asString())
    }

    private fun usuario(
        email: String,
        rol: String
    ) = AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), email, listOf(rol))

    private fun recibo() =
        Recibo(
            id = "r-1",
            serie = "001",
            numero = 1,
            numeroImpreso = "001-0000001",
            caja = "c-1",
            turno = "t-1",
            cajero = "ana@muni.test",
            pagadorDocumento = "12345678",
            pagadorNombre = "FLORES OTINIANO JUNIOR",
            pagadorExternoId = 1234,
            emitidoEn = Instant.parse("2026-10-02T15:15:30.123456Z"),
            formaPago = "EFECTIVO",
            tipoPago = "NORMAL",
            total = BigDecimal("150.50"),
            actualizadoA = hoy,
            observacion = "cobro en ventanilla"
        )

    private fun lineas() =
        listOf(
            LineaRecibo(id = "l-1", recibo = "r-1", orden = "o-1", sistemaOrigen = "rentas", concepto = "PREDIAL 1", monto = BigDecimal("100.00")),
            LineaRecibo(id = "l-2", recibo = "r-1", orden = "o-2", sistemaOrigen = "rentas", concepto = "PREDIAL 2", monto = BigDecimal("50.50"))
        )
}
