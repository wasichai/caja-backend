package caja.emision

import caja.cobro.Caja
import caja.cobro.LineaRecibo
import caja.cobro.NORMAL
import caja.cobro.Recibo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// el original del recibo: lo que el papel dice, en la hora de Lima, con sus importes y la fecha a la que están
class ReciboPdfTest {
    private val pdf = ReciboPdf(PdfRenderer(), Municipalidad("MUNICIPALIDAD DISTRITAL DE PERENÉ"))

    @Test
    fun `el original lleva todo lo que el papel tiene que decir`() {
        val texto = texto(pdf.original(recibo(), caja(), lineas()))
        listOf(
            "MUNICIPALIDAD DISTRITAL DE PERENÉ",
            "001-0000005",
            // 15:00 UTC son las 10:00 en Lima
            "02/10/2026 10:00:00",
            "C-01 — Ventanilla 1",
            "cajero@caja.test",
            "FLORES OTINIANO JUNIOR",
            "12345678",
            "IMPUESTO PREDIAL 2026 - CUOTA 1",
            "predio U-0001",
            "PREDIAL-2026-0001",
            "S/ 1,150.50",
            "S/ 12.50",
            "S/ 1,163.00",
            "Importes actualizados al 02/10/2026",
            "EFECTIVO",
            "cobro en ventanilla, cuota 1"
        ).forEach { assertTrue(it in texto, "falta «$it» en:\n$texto") }
    }

    @Test
    fun `sin pagador identificado se dice, no se deja en blanco`() {
        val anonimo = recibo().copy(pagadorDocumento = null, pagadorNombre = null, pagadorExternoId = null)
        val texto = texto(pdf.original(anonimo, caja(), lineas()))
        assertTrue("— (no se identificó al pagador)" in texto, texto)
    }

    @Test
    fun `la municipalidad es obligatoria`() {
        assertThrows<IllegalStateException> { Municipalidad("  ") }
        assertEquals("MUNICIPALIDAD X", Municipalidad(" MUNICIPALIDAD X ").nombre)
    }

    private fun recibo() =
        Recibo(
            id = UUID.randomUUID().toString(),
            serie = "001",
            numero = 5,
            numeroImpreso = "001-0000005",
            caja = UUID.randomUUID().toString(),
            turno = UUID.randomUUID().toString(),
            cajero = "cajero@caja.test",
            pagadorDocumento = "12345678",
            pagadorNombre = "FLORES OTINIANO JUNIOR",
            pagadorExternoId = 1234,
            emitidoEn = Instant.parse("2026-10-02T15:00:00Z"),
            formaPago = "EFECTIVO",
            tipoPago = NORMAL,
            total = BigDecimal("1163.00"),
            actualizadoA = LocalDate.of(2026, 10, 2),
            claveIdempotencia = null,
            observacion = "cobro en ventanilla, cuota 1"
        )

    private fun caja() = Caja(UUID.randomUUID().toString(), "C-01", "Ventanilla 1", "001", true, null)

    private fun lineas() =
        listOf(
            LineaRecibo(
                concepto = "IMPUESTO PREDIAL 2026 - CUOTA 1",
                detalle = "predio U-0001",
                referenciaExterna = "PREDIAL-2026-0001",
                sistemaOrigen = "rentas",
                monto = BigDecimal("1150.50")
            ),
            LineaRecibo(concepto = "ARBITRIOS 2026", referenciaExterna = "ARB-2026-0001", sistemaOrigen = "rentas", monto = BigDecimal("12.5"))
        )
}
