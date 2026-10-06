package caja.cobro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// las reglas puras de la cobranza: el número del papel, el total, una sola fuente, cobrable a la fecha, el cuerpo del
// evento y lo que llega en la petición
class CobranzaTest {
    private val hoy = LocalDate.of(2026, 10, 2)

    // el número impreso: NumeroDeRecibo de caja

    @Test
    fun `el numero impreso lleva la serie, un guion y siete digitos`() {
        assertEquals("001-0000001", numeroImpreso("001", 1))
        assertEquals("001-0000123", numeroImpreso("001", 123))
        assertEquals("C10-1234567", numeroImpreso("C10", 1_234_567))
        // más de siete dígitos no se corta
        assertEquals("A-12345678", numeroImpreso("A", 12_345_678))
    }

    @Test
    fun `la serie va recortada y en mayusculas, de 1 a 5 caracteres`() {
        assertEquals("AB1-0000007", numeroImpreso(" ab1 ", 7))
        assertEquals("ABCDE-0000007", numeroImpreso("abcde", 7))
        listOf("", "   ", "ABCDEF").forEach { assertThrows<IllegalArgumentException>(it) { numeroImpreso(it, 1) } }
    }

    @Test
    fun `el correlativo empieza en 1`() {
        assertThrows<IllegalArgumentException> { numeroImpreso("001", 0) }
        assertThrows<IllegalArgumentException> { numeroImpreso("001", -1) }
    }

    // el total: la suma de las líneas, nunca otra cifra

    @Test
    fun `el total es la suma exacta de las lineas, sin redondear`() {
        assertEquals(BigDecimal("0.30"), totalDe(listOf(BigDecimal("0.10"), BigDecimal("0.20"))))
        assertEquals(BigDecimal("163.0"), totalDe(listOf(BigDecimal("150.5"), BigDecimal("12.5"))))
        assertEquals(BigDecimal("10000000000000.01"), totalDe(listOf(BigDecimal("9999999999999.99"), BigDecimal("0.02"))))
        assertThrows<IllegalArgumentException> { totalDe(emptyList()) }
    }

    // un recibo cobra órdenes de un solo sistema: se anula entero

    @Test
    fun `ordenes de un solo sistema dan ese sistema`() {
        assertEquals("rentas", sistemaUnico(listOf(orden(), orden())))
    }

    @Test
    fun `ordenes de dos sistemas dan 400 en ordenes`() {
        val error = assertThrows<ValidationException> { sistemaUnico(listOf(orden(), orden(sistemaOrigen = "mercados"))) }
        assertEquals("ordenes", error.violations.first().field)
    }

    // cobrable a la fecha: OrdenDeCobro.cobrableA de caja

    @Test
    fun `una orden pendiente y exigible es cobrable`() {
        assertTrue(orden(fechaExigibilidad = hoy).cobrableA(hoy))
        assertTrue(orden(fechaExigibilidad = hoy.minusDays(30)).cobrableA(hoy))
        assertNull(motivoNoCobrable(orden(), hoy))
    }

    @Test
    fun `una orden pagada, anulada o que aun no es exigible no es cobrable y se dice por que`() {
        val pagada = orden(estado = PAGADA, recibo = UUID.randomUUID().toString())
        val anulada = orden(estado = ANULADA)
        val futura = orden(fechaExigibilidad = hoy.plusDays(1))
        listOf(pagada, anulada, futura).forEach { assertFalse(it.cobrableA(hoy), it.toString()) }
        assertTrue(motivoNoCobrable(pagada, hoy)!!.contains("ya se cobró"))
        assertTrue(motivoNoCobrable(anulada, hoy)!!.contains("la retiró"))
        assertTrue(motivoNoCobrable(futura, hoy)!!.contains("es exigible desde el ${hoy.plusDays(1)}"))
        assertTrue(motivoNoCobrable(futura, hoy)!!.contains(futura.id!!))
    }

    // una orden escrita en la base no pasó por el alta: el cobro vuelve a mirar su importe, y un
    // importe que el alta habría rechazado es un dato roto, un 409 que nombra la orden. nunca un recibo en negativo
    @Test
    fun `una orden con el importe roto no se cobra, y se dice que es un dato roto y cual`() {
        val rotas =
            listOf("-50.00", "0", "0.00", "10.005", "10000000000000.00")
                .map { orden(importe = BigDecimal(it)) } + orden().copy(importe = null)

        rotas.forEach { rota ->
            val motivo = motivoNoCobrable(rota, hoy)
            assertTrue(motivo != null && motivo.contains("dato roto") && motivo.contains(rota.id!!), "${rota.importe}: $motivo")
            val impedimento = impedimentosDelCobro(listOf(rota.id!!), mapOf(rota.id to rota), hoy).single()
            assertTrue(impedimento is ConflictException, impedimento.toString())
        }
        // el mismo límite que el alta: 13 enteros y 2 decimales valen
        assertNull(motivoNoCobrable(orden(importe = BigDecimal("9999999999999.99")), hoy))
        assertNull(motivoNoCobrable(orden(importe = BigDecimal("0.01")), hoy))
    }

    @Test
    fun `el importe roto es la regla del alta, con sus mismas palabras`() {
        assertEquals("debe ser mayor que 0", defectoDelImporte(BigDecimal("-1")))
        assertEquals("a lo sumo 2 decimales", defectoDelImporte(BigDecimal("1.001")))
        assertEquals("a lo sumo $ENTEROS_DEL_IMPORTE dígitos enteros", defectoDelImporte(BigDecimal("10000000000000")))
        assertNull(defectoDelImporte(BigDecimal("150.50")))
        assertTrue(defectoDelImporte(null)!!.isNotBlank())
    }

    // lo que impide cobrar unas órdenes: lo mismo para el cobro (que lanza el primero) y la vista previa (que los dice)

    @Test
    fun `lo que impide el cobro va en el orden del cobro, primero la que no existe y luego la que no es cobrable`() {
        val buena = orden()
        val pagada = orden(estado = PAGADA, recibo = UUID.randomUUID().toString())
        val falta = UUID.randomUUID().toString()
        val leidas = listOf(buena, pagada).associateBy { it.id!! }

        val impedimentos = impedimentosDelCobro(listOf(pagada.id!!, falta, buena.id!!), leidas, hoy)

        assertEquals(2, impedimentos.size)
        assertTrue(impedimentos[0] is NotFoundException && impedimentos[0].message.contains(falta), impedimentos.toString())
        assertTrue(impedimentos[1] is ConflictException && impedimentos[1].message.contains(pagada.id), impedimentos.toString())
        assertTrue(impedimentosDelCobro(listOf(buena.id), leidas, hoy).isEmpty())
    }

    @Test
    fun `ordenes de dos sistemas impiden el cobro con un 400 en ordenes`() {
        val rentas = orden()
        val mercados = orden(sistemaOrigen = "mercados")

        val impedimento = impedimentosDelCobro(listOf(rentas.id!!, mercados.id!!), listOf(rentas, mercados).associateBy { it.id!! }, hoy).single()

        assertEquals("ordenes", (impedimento as ValidationException).violations.single().field)
        assertTrue(motivo(impedimento).contains("«mercados»"), motivo(impedimento))
    }

    @Test
    fun `la linea de una orden copia su sistema, concepto, detalle, referencia e importe`() {
        val orden = orden(importe = BigDecimal("150.50")).copy(detalle = "predio U-0001")

        val linea = lineaDeOrden(orden)

        assertEquals(orden.id, linea.orden)
        assertEquals("rentas", linea.sistemaOrigen)
        assertEquals(orden.concepto, linea.concepto)
        assertEquals("predio U-0001", linea.detalle)
        assertEquals(orden.referenciaExterna, linea.referenciaExterna)
        assertEquals(BigDecimal("150.50"), linea.monto)
        assertNull(linea.tasa)
        assertNull(linea.cantidad)
    }

    // el cuerpo de PAGO_REGISTRADO: las claves de rentas.json, los importes en cadena

    @Test
    fun `el cuerpo de PAGO_REGISTRADO tiene exactamente las claves del contrato de rentas`() {
        val ordenes = listOf(orden(importe = BigDecimal("150.50")), orden(importe = BigDecimal("12.5")))
        val pagoId = UUID.randomUUID()
        val cuerpo = JsonMapper.builder().build().readTree(cuerpoPagoRegistrado(pagoId, recibo(), ordenes))

        assertEquals(PAGO_REGISTRADO_RAIZ, cuerpo.claves())
        assertEquals(PAGO_REGISTRADO_RECIBO, cuerpo["recibo"].claves())
        assertEquals(PAGO_REGISTRADO_PAGADOR, cuerpo["pagador"].claves())
        // toList: JsonNode.map de jackson 3 transforma el nodo entero, no sus elementos
        val lineas = cuerpo["ordenes"].toList()
        assertEquals(2, lineas.size)
        lineas.forEach { assertEquals(PAGO_REGISTRADO_ORDEN, it.claves()) }

        assertEquals(pagoId.toString(), cuerpo["pagoId"].asString())
        assertEquals("PAGO_REGISTRADO", cuerpo["tipo"].asString())
        assertEquals("rentas", cuerpo["sistemaOrigen"].asString())
        // regla 1: los importes viajan en cadena
        assertTrue(cuerpo["total"].isString)
        assertEquals("163.00", cuerpo["total"].asString())
        assertEquals("2026-10-02", cuerpo["actualizadoA"].asString())
        assertEquals("001-0000005", cuerpo["recibo"]["numero"].asString())
        assertEquals("001", cuerpo["recibo"]["serie"].asString())
        assertEquals("2026-10-02", cuerpo["recibo"]["fechaDePago"].asString())
        assertEquals("cajero@caja.test", cuerpo["recibo"]["cajero"].asString())
        assertEquals("EFECTIVO", cuerpo["recibo"]["formaDePago"].asString())
        assertEquals("12345678", cuerpo["pagador"]["documento"].asString())
        assertEquals(1234L, cuerpo["pagador"]["idExterno"].asLong())
        assertTrue(cuerpo["pagador"]["idExterno"].isIntegralNumber)
        // ordenId: el uuid de la orden en cadena (en caja era un entero)
        assertEquals(ordenes.map { it.id }, lineas.map { it["ordenId"].asString() })
        assertEquals(listOf("150.50", "12.5"), lineas.map { it["importe"].asString() })
        assertTrue(lineas.all { it["importe"].isString })
        assertEquals(listOf("2026-03-15", "2026-03-15"), lineas.map { it["actualizadoA"].asString() })
    }

    @Test
    fun `un pagador anonimo viaja con sus tres claves en null`() {
        val cuerpo =
            JsonMapper.builder().build().readTree(
                cuerpoPagoRegistrado(
                    UUID.randomUUID(),
                    recibo().copy(pagadorDocumento = null, pagadorNombre = null, pagadorExternoId = null),
                    listOf(orden())
                )
            )
        assertEquals(PAGO_REGISTRADO_PAGADOR, cuerpo["pagador"].claves())
        assertTrue(cuerpo["pagador"].toList().all { it.isNull })
    }

    // el nombre del papel: nunca la cadena vacía (RNF-080 de caja)

    @Test
    fun `el pagador se imprime por su nombre, si no por su documento, si no se dice que no se identifico`() {
        assertEquals("FLORES OTINIANO JUNIOR", nombreImpreso("FLORES OTINIANO JUNIOR", "12345678"))
        assertEquals("12345678", nombreImpreso(null, "12345678"))
        assertEquals("— (no se identificó al pagador)", nombreImpreso(null, null))
    }

    // lo que llega en la petición

    @Test
    fun `la forma de pago es una de las cinco`() {
        assertEquals("EFECTIVO", formaDePago(" efectivo "))
        assertEquals("TRANSFERENCIA", formaDePago("TRANSFERENCIA"))
        listOf(null, "", "BITCOIN").forEach { assertEquals("forma_pago", rechazado { formaDePago(it) }, it.toString()) }
    }

    @Test
    fun `las ordenes marcadas son uuids, al menos una y ninguna dos veces`() {
        val una = UUID.randomUUID()
        val otra = UUID.randomUUID()
        assertEquals(listOf(una, otra), ordenesMarcadas(listOf(una.toString(), " $otra ")))
        assertEquals("ordenes", rechazado { ordenesMarcadas(null) })
        assertEquals("ordenes", rechazado { ordenesMarcadas(emptyList()) })
        assertEquals("ordenes", rechazado { ordenesMarcadas(listOf("12")) })
        assertEquals("ordenes", rechazado { ordenesMarcadas(listOf(una.toString(), una.toString().uppercase())) })
    }

    @Test
    fun `la clave de idempotencia es opcional y de 1 a 64 caracteres`() {
        assertNull(claveDeIdempotencia(null))
        assertEquals("abc", claveDeIdempotencia(" abc "))
        assertEquals("x".repeat(64), claveDeIdempotencia("x".repeat(64)))
        assertEquals("Idempotency-Key", rechazado { claveDeIdempotencia("  ") })
        assertEquals("Idempotency-Key", rechazado { claveDeIdempotencia("x".repeat(65)) })
    }

    @Test
    fun `la fecha de pago es hoy, o no viene`() {
        assertEquals(hoy, fechaDePago(null, hoy))
        assertEquals(hoy, fechaDePago(" ", hoy))
        assertEquals(hoy, fechaDePago("2026-10-02", hoy))
        listOf("2026-10-01", "2026-10-03", "02/10/2026").forEach { assertEquals("fecha_de_pago", rechazado { fechaDePago(it, hoy) }, it) }
    }

    @Test
    fun `la fecha de cobro de las tasas sigue la misma regla, sobre su campo`() {
        assertEquals(hoy, fechaDePago(null, hoy, "fecha_de_cobro"))
        assertEquals("fecha_de_cobro", rechazado { fechaDePago("2026-10-01", hoy, "fecha_de_cobro") })
    }

    @Test
    fun `el cajero es el de la sesion, y otro en el cuerpo es 403`() {
        assertEquals("ana@caja.test", cajeroDeLaSesion(null, "ana@caja.test"))
        assertEquals("ana@caja.test", cajeroDeLaSesion(" ", "ana@caja.test"))
        assertEquals("ana@caja.test", cajeroDeLaSesion(" ana@caja.test ", "ana@caja.test"))
        val error = assertThrows<ForbiddenException> { cajeroDeLaSesion("luis@caja.test", "ana@caja.test") }
        assertTrue(error.message.contains("luis@caja.test"), error.message)
    }

    @Test
    fun `el codigo de caja no puede faltar`() {
        assertEquals("C-01", codigoDeCaja(" C-01 "))
        assertEquals("caja", rechazado { codigoDeCaja(" ") })
    }

    private fun rechazado(regla: () -> Unit): String =
        assertThrows<ValidationException> { regla() }
            .violations
            .first()
            .field

    private fun JsonNode.claves(): Set<String> = propertyNames().toSet()

    private fun orden(
        sistemaOrigen: String = "rentas",
        estado: String = PENDIENTE,
        fechaExigibilidad: LocalDate = LocalDate.of(2026, 2, 28),
        importe: BigDecimal = BigDecimal("150.50"),
        recibo: String? = null
    ) = OrdenDeCobro(
        id = UUID.randomUUID().toString(),
        sistemaOrigen = sistemaOrigen,
        referenciaExterna = "PREDIAL-2026-${UUID.randomUUID()}",
        concepto = "IMPUESTO PREDIAL 2026 - CUOTA 1",
        detalle = null,
        importe = importe,
        fechaExigibilidad = fechaExigibilidad,
        actualizadoA = LocalDate.of(2026, 3, 15),
        pagadorDocumento = "12345678",
        pagadorNombre = "FLORES OTINIANO JUNIOR",
        pagadorExternoId = 1234,
        estado = estado,
        observacion = "emisión de la cuota 1",
        recibo = recibo
    )

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
            total = BigDecimal("163.00"),
            actualizadoA = LocalDate.of(2026, 10, 2),
            claveIdempotencia = null,
            observacion = "cobro en ventanilla"
        )

    private companion object {
        // docs/50-api/contratos-que-consume/rentas.json de caja, POST /pagos: las claves de PAGO_REGISTRADO (las de
        // PAGO_ANULADO, pagoOriginalId, motivo y fecha, no van)
        val PAGO_REGISTRADO_RAIZ = setOf("pagoId", "tipo", "sistemaOrigen", "total", "actualizadoA", "recibo", "pagador", "ordenes")
        val PAGO_REGISTRADO_RECIBO = setOf("numero", "serie", "fechaDePago", "cajero", "formaDePago")
        val PAGO_REGISTRADO_PAGADOR = setOf("documento", "nombre", "idExterno")
        val PAGO_REGISTRADO_ORDEN = setOf("ordenId", "referenciaExterna", "importe", "actualizadoA")
    }
}
