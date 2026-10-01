package caja.cobro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

class ReglasTest {
    // sistema de origen: la mitad de la clave de idempotencia. dos formas de teclearlo no son dos sistemas

    @Test
    fun `el sistema de origen se guarda recortado y en minusculas`() {
        assertEquals("rentas", sistemaOrigen("  Rentas "))
        assertEquals("mercado_2-b", sistemaOrigen("MERCADO_2-b"))
        assertEquals("x".repeat(20), sistemaOrigen("x".repeat(20)))
    }

    @Test
    fun `un sistema de origen vacio, largo o con otros caracteres se rechaza`() {
        listOf(null, "", "   ", "x".repeat(21), "ren tas", "rentás", "rentas|2", "rentas.pe").forEach {
            assertEquals("sistema_origen", rechazado { sistemaOrigen(it) }, it.toString())
        }
    }

    // referencia externa: opaca. solo se recorta, y no puede faltar

    @Test
    fun `la referencia externa es opaca y solo se recorta`() {
        assertEquals("2026/PREDIAL|C-01 ñ", referenciaExterna("  2026/PREDIAL|C-01 ñ "))
        assertEquals("referencia_externa", rechazado { referenciaExterna("  ") })
        assertEquals("referencia_externa", rechazado { referenciaExterna("x".repeat(121)) })
    }

    @Test
    fun `la clave de origen une sistema y referencia con una barra`() {
        assertEquals("rentas|2026-000123", claveDeOrigen("rentas", "2026-000123"))
        // el sistema no puede llevar barra: la primera barra parte la clave sin ambigüedad
        assertEquals("rentas|a|b", claveDeOrigen("rentas", "a|b"))
    }

    @Test
    fun `el concepto no puede faltar y el detalle si`() {
        assertEquals("IMPUESTO PREDIAL", concepto(" IMPUESTO PREDIAL "))
        assertEquals("concepto", rechazado { concepto(" ") })
        assertEquals("concepto", rechazado { concepto("x".repeat(121)) })
        assertNull(detalle("   "))
        assertEquals("cuota 1", detalle(" cuota 1 "))
        assertEquals("detalle", rechazado { detalle("x".repeat(201)) })
    }

    // importe: regla 1, BigDecimal desde una cadena

    @Test
    fun `un importe positivo de hasta dos decimales se lee como BigDecimal tal cual`() {
        assertEquals(BigDecimal("10080.45"), importe("10080.45"))
        assertEquals(BigDecimal("0.01"), importe("0.01"))
        assertEquals(BigDecimal("12.5"), importe(" 12.5 "))
        assertEquals(BigDecimal("7"), importe("7"))
    }

    @Test
    fun `un importe cero, negativo o con tres decimales se rechaza`() {
        listOf("0", "0.00", "-5", "-0.01", "1.005", "12.345").forEach {
            assertEquals("importe", rechazado { importe(it) }, it)
        }
    }

    @Test
    fun `lo que no es un importe escrito en decimal se rechaza`() {
        listOf(null, "", "abc", "1,50", "1e2", "NaN", "Infinity", "1.", ".5", "+5", "١٢").forEach {
            assertEquals("importe", rechazado { importe(it) }, it.toString())
        }
    }

    @Test
    fun `una fecha es ISO y no puede faltar`() {
        assertEquals(LocalDate.of(2026, 3, 31), fecha("2026-03-31", "fecha_exigibilidad"))
        listOf(null, "", "31/03/2026", "2026-02-30").forEach {
            assertEquals("actualizado_a", rechazado { fecha(it, "actualizado_a") }, it.toString())
        }
    }

    // pagador: los tres campos son opcionales, la caja guarda lo que le digan

    @Test
    fun `el pagador puede ser anonimo`() {
        assertEquals(Pagador(null, null, null), pagador(" ", null, null))
    }

    @Test
    fun `el documento va en mayusculas y el nombre recortado`() {
        assertEquals(Pagador("AB123", "FLORES OTINIANO JUNIOR", 42), pagador(" ab123 ", " FLORES OTINIANO JUNIOR ", 42))
    }

    @Test
    fun `un pagador que no cabe o un id que no es un id se rechaza`() {
        assertEquals("pagador_documento", rechazado { pagador("x".repeat(21), null, null) })
        assertEquals("pagador_nombre", rechazado { pagador(null, "x".repeat(151), null) })
        assertEquals("pagador_externo_id", rechazado { pagador(null, null, 0) })
        assertEquals("pagador_externo_id", rechazado { pagador(null, null, -3) })
    }

    // estado: el filtro de la ventanilla

    @Test
    fun `sin estado se piden las pendientes`() {
        assertEquals("PENDIENTE", estadoOrden(null))
        assertEquals("PENDIENTE", estadoOrden(" "))
        assertEquals("PAGADA", estadoOrden("pagada"))
        assertEquals("estado", rechazado { estadoOrden("VENCIDA") })
    }

    // la frontera: una orden no lleva tributo, ejercicio ni periodo, ni nada que la caja no conozca

    @Test
    fun `un campo desconocido se rechaza con su nombre`() {
        sinCamposDesconocidos(emptyList())
        assertEquals("tributo", rechazado { sinCamposDesconocidos(listOf("tributo", "ejercicio")) })
    }

    private fun rechazado(regla: () -> Unit): String =
        assertThrows<ValidationException> { regla() }
            .violations
            .first()
            .field
}
