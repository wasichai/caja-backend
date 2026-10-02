package caja.cobro

import caja.CajaApiTest
import caja.comun.LIMA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

// POST /api/caja/cobros/tasas: el precio sale de la tarifa vigente a la fecha, nunca de la petición, y el recibo se
// emite con el mismo mecanismo que el cobro de órdenes (turno, candados, idempotencia, número), sin evento. y
// GET /api/caja/tasas, la lista que ofrece la ventanilla
@ExtendWith(OutputCaptureExtension::class)
class TasasApiTest : CajaApiTest() {
    private val hoy: LocalDate get() = LocalDate.now(LIMA)

    @Test
    fun `el precio sale de la tarifa vigente a la fecha y no de la peticion`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        // tres vigencias: la de ayer, la de hoy (cerrada en 30 días) y una futura
        nuevaTasa(codigo, "10.00", hoy.minusDays(60), hoy.minusDays(1))
        val vigente = nuevaTasa(codigo, "12.30", hoy, hoy.plusDays(30), descripcion = "CONSTANCIA DE NO ADEUDO")
        nuevaTasa(codigo, "15.00", hoy.plusDays(31))

        // con un precio o un importe en el cuerpo, 400 y nada emitido
        rejected("POST", TASAS, cobro(caja, concepto(codigo, 3)) + ("importe" to "1.00"), "importe", cajero.token)
        rejected("POST", TASAS, cobro(caja, mapOf("codigo" to codigo, "cantidad" to 3, "precio" to "1.00")), "conceptos[0].precio", cajero.token)
        assertEquals(0, registros("recibo", "caja" to caja.id).size)

        val cobro = post(TASAS, cobro(caja, concepto(codigo, 3)), cajero.token)

        val recibo = cobro["recibo"]
        assertEquals("TASA", recibo["tipo_pago"].asString())
        assertEquals("36.90", recibo["total"]["importe"].asString())
        assertEquals(hoy.toString(), recibo["total"]["actualizado_a"].asString())
        val linea = recibo["lineas"].single()
        assertEquals(codigo, linea["codigo"].asString())
        assertEquals("CONSTANCIA DE NO ADEUDO", linea["concepto"].asString())
        assertEquals(3L, linea["cantidad"].asLong())
        assertEquals("12.30", linea["precio_unitario"]["importe"].asString())
        assertEquals("36.90", linea["monto"]["importe"].asString())
        assertEquals("SIN_EVENTO", cobro["estado_del_pago"].asString())
        assertTrue(cobro["pago_id"].isNull, cobro.toString())
        assertTrue(cobro["emitido"].asBoolean())

        // lo guardado, leído como admin
        val guardado = registros("recibo", "numero_impreso" to recibo["numero_impreso"].asString()).single()
        assertEquals("TASA", guardado["attributes"]["tipo_pago"].asString())
        assertEquals(hoy.toString(), guardado["attributes"]["actualizado_a"].asString())
        val guardada = registros("linea_recibo", "recibo" to guardado["id"].asString()).single()["attributes"]
        assertEquals(vigente, guardada["tasa"].asString())
        assertEquals("CONSTANCIA DE NO ADEUDO", guardada["concepto"].asString())
        assertEquals(3L, guardada["cantidad"].asLong())
        assertEquals(0, guardada["precio_unitario"].decimalValue().compareTo(java.math.BigDecimal("12.30")))
        assertEquals(0, guardada["monto"].decimalValue().compareTo(java.math.BigDecimal("36.90")))
        assertTrue(guardada["orden"].isNull, guardada.toString())
        // una tasa no produce evento
        assertEquals(0, registros("pago_evento", "recibo" to guardado["id"].asString()).size)
        // y abrió el turno del cajero
        assertEquals(1, registros("turno", "clave_turno" to "${caja.id}|${cajero.email}|$hoy").size)
    }

    @Test
    fun `una tasa sin tarifa vigente da 404 y una tarifa en cero da 409`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val futura = codigoDeTasa()
        nuevaTasa(futura, "12.30", hoy.plusDays(1))
        val vencida = codigoDeTasa()
        nuevaTasa(vencida, "12.30", hoy.minusDays(30), hoy.minusDays(1))
        val enCero = codigoDeTasa()
        nuevaTasa(enCero, "0.00", hoy.minusDays(30))

        listOf(futura, vencida, "T-NO-EXISTE").forEach {
            val problema = tree(send("POST", TASAS, cobro(caja, concepto(it)), HttpStatus.NOT_FOUND, cajero.token))
            assertTrue(problema["detail"].asString().contains("'$it'"), problema.toString())
            assertTrue(problema["detail"].asString().contains("conceptos"), problema.toString())
        }
        val problema = tree(send("POST", TASAS, cobro(caja, concepto(enCero)), HttpStatus.CONFLICT, cajero.token))
        assertTrue(problema["detail"].asString().contains("tarifa en cero"), problema.toString())

        // nada quedó: ni recibo ni turno
        assertEquals(0, registros("recibo", "caja" to caja.id).size)
        assertEquals(0, registros("turno", "caja" to caja.id).size)
    }

    @Test
    fun `el reenvio con la misma clave devuelve el mismo recibo`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        val clave = mapOf("Idempotency-Key" to UUID.randomUUID().toString())

        val primero = tree(send("POST", TASAS, cobro(caja, concepto(codigo, 2)), HttpStatus.CREATED, cajero.token, clave))
        val segundo = tree(send("POST", TASAS, cobro(caja, concepto(codigo, 2)), HttpStatus.OK, cajero.token, clave))

        assertFalse(segundo["emitido"].asBoolean())
        assertEquals(primero["recibo"].toString(), segundo["recibo"].toString())
        assertEquals("SIN_EVENTO", segundo["estado_del_pago"].asString())
        assertEquals(1, registros("recibo", "caja" to caja.id).size)

        // la clave de un cobro de órdenes del mismo cajero no se reusa para tasas: es otro cobro
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        val otraClave = mapOf("Idempotency-Key" to UUID.randomUUID().toString())
        send("POST", COBROS, cobroDeOrdenes(caja, ordenId), HttpStatus.CREATED, cajero.token, otraClave)
        send("POST", TASAS, cobro(caja, concepto(codigo)), HttpStatus.CONFLICT, cajero.token, otraClave)
        assertEquals(2, registros("recibo", "caja" to caja.id).size)
    }

    @Test
    fun `el pagador puede ser anonimo o venir dicho`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))

        val anonimo = post(TASAS, cobro(caja, concepto(codigo)), cajero.token)["recibo"]["numero_impreso"].asString()
        val guardado = registros("recibo", "numero_impreso" to anonimo).single()["attributes"]
        assertTrue(listOf("pagador_documento", "pagador_nombre", "pagador_externo_id").all { guardado[it] == null || guardado[it].isNull }, guardado.toString())

        val dicho =
            post(
                TASAS,
                cobro(caja, concepto(codigo)) + mapOf("pagador_documento" to "12345678", "pagador_nombre" to "SANTOS RIVERA, ELENA", "pagador_externo_id" to 7),
                cajero.token
            )["recibo"]["numero_impreso"].asString()
        val conPagador = registros("recibo", "numero_impreso" to dicho).single()["attributes"]
        assertEquals("12345678", conPagador["pagador_documento"].asString())
        assertEquals("SANTOS RIVERA, ELENA", conPagador["pagador_nombre"].asString())
        assertEquals(7L, conPagador["pagador_externo_id"].asLong())

        // el original en pdf de un recibo de tasas, para quien lo cobró
        send("GET", "/api/caja/recibos/$anonimo/pdf", null, HttpStatus.OK, cajero.token)
    }

    @Test
    fun `lo que no se puede cobrar se dice con su codigo`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))

        // sin observación, sin conceptos, una cantidad de 0, sin código, una forma de pago o una fecha que no son: 400
        rejected("POST", TASAS, cobro(caja, concepto(codigo)) - "observacion", "observacion", cajero.token)
        rejected("POST", TASAS, cobro(caja), "conceptos", cajero.token)
        rejected("POST", TASAS, cobro(caja, concepto(codigo)) - "conceptos", "conceptos", cajero.token)
        rejected("POST", TASAS, cobro(caja, concepto(codigo, 0)), "conceptos[0].cantidad", cajero.token)
        rejected("POST", TASAS, cobro(caja, mapOf("cantidad" to 1)), "conceptos[0].codigo", cajero.token)
        rejected("POST", TASAS, cobro(caja, concepto(codigo)) + ("forma_pago" to "BITCOIN"), "forma_pago", cajero.token)
        rejected("POST", TASAS, cobro(caja, concepto(codigo)) + ("fecha_de_cobro" to hoy.minusDays(1).toString()), "fecha_de_cobro", cajero.token)
        rejected("POST", TASAS, cobro(caja, concepto(codigo)) + ("tributo" to "PREDIAL"), "tributo", cajero.token)
        // todos los campos que fallan, en un solo 400
        val todos =
            tree(
                send(
                    "POST",
                    TASAS,
                    cobro(caja, mapOf("codigo" to codigo, "cantidad" to 0, "precio" to "1.00"), mapOf("cantidad" to 2)) + ("importe" to "9.99") -
                        "forma_pago",
                    HttpStatus.BAD_REQUEST,
                    cajero.token
                )
            )
        assertEquals(
            setOf("importe", "forma_pago", "conceptos[0].cantidad", "conceptos[0].precio", "conceptos[1].codigo"),
            todos["errors"].toList().map { it["field"].asString() }.toSet(),
            todos.toString()
        )
        // otro cajero: 403
        send("POST", TASAS, cobro(caja, concepto(codigo)) + ("cajero" to "otro@caja.test"), HttpStatus.FORBIDDEN, cajero.token)
        // una caja inexistente, 404; una de baja, 409
        send("POST", TASAS, cobro(caja, concepto(codigo)) + ("caja" to "C-NO-EXISTE"), HttpStatus.NOT_FOUND, cajero.token)
        send("POST", TASAS, cobro(nuevaCaja(activa = false), concepto(codigo)), HttpStatus.CONFLICT, cajero.token)
        assertEquals(0, registros("recibo", "caja" to caja.id).size)

        // sin cantidad, una vez; con fecha de hoy y el cajero de la sesión dichos, vale
        val cobro =
            post(TASAS, cobro(caja, mapOf("codigo" to codigo)) + ("fecha_de_cobro" to hoy.toString()) + ("cajero" to cajero.email), cajero.token)
        assertEquals(1L, cobro["recibo"]["lineas"].single()["cantidad"].asLong())
        assertEquals("12.30", cobro["recibo"]["total"]["importe"].asString())
    }

    @Test
    fun `la numeracion se comparte con el cobro de ordenes de la misma caja`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        val ordenId = post(ORDENES, orden())["orden_id"].asString()

        val deOrdenes = post(COBROS, cobroDeOrdenes(caja, ordenId), cajero.token)["recibo"]
        val deTasas = post(TASAS, cobro(caja, concepto(codigo)), cajero.token)["recibo"]

        assertEquals("${caja.serie}-0000001", deOrdenes["numero_impreso"].asString())
        assertEquals("${caja.serie}-0000002", deTasas["numero_impreso"].asString())
        // y el mismo turno
        assertEquals(1, registros("turno", "caja" to caja.id).size)
        // solo el de órdenes dejó evento
        val eventos = registros("recibo", "caja" to caja.id).sumOf { registros("pago_evento", "recibo" to it["id"].asString()).size }
        assertEquals(1, eventos)
    }

    @Test
    fun `un CAJERO cobra tasas y TESORERIA recibe 403`() {
        val caja = nuevaCaja()
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))

        val problema = tree(send("POST", TASAS, cobro(caja, concepto(codigo)), HttpStatus.FORBIDDEN, funcionario("TESORERIA")))
        assertTrue(problema["detail"].asString().contains("recibo"), problema.toString())
        assertEquals(0, registros("recibo", "caja" to caja.id).size)
        post(TASAS, cobro(caja, concepto(codigo)), funcionario("SUPERVISOR_CAJA"))
        assertEquals(1, registros("recibo", "caja" to caja.id).size)
    }

    @Test
    fun `el codigo de la tasa se lee con trim y en mayusculas`() {
        val caja = nuevaCaja()
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))

        val cobro = post(TASAS, cobro(caja, concepto("  ${codigo.lowercase()} ", 2)), funcionario("CAJERO"))

        assertEquals(codigo, cobro["recibo"]["lineas"].single()["codigo"].asString())
        assertEquals("24.60", cobro["recibo"]["total"]["importe"].asString())
    }

    @Test
    fun `una vigencia al reves no tumba la lista ni la vista previa`(salida: CapturedOutput) {
        val buena = codigoDeTasa()
        nuevaTasa(buena, "12.30", hoy.minusDays(1))
        // un dato mal cargado en el admin: termina antes de empezar (import_tasas.py la habría rechazado)
        val alReves = codigoDeTasa()
        nuevaTasa(alReves, "5.00", hoy, hoy.minusDays(10))

        // la lista sale sin esa fila, y queda una línea WARN que la nombra
        val lista = lista("/api/caja/tasas", funcionario("CAJERO"))
        assertTrue(lista.any { it["codigo"].asString() == buena })
        assertTrue(lista.none { it["codigo"].asString() == alReves })
        assertTrue(salida.out.lines().any { "WARN" in it && alReves in it && "termina antes de empezar" in it }, "falta la línea WARN de $alReves")

        // la vista previa la dice en motivos y cotiza lo demás
        val vista =
            tree(
                send(
                    "POST",
                    "$TASAS/vista-previa",
                    mapOf("conceptos" to listOf(concepto(buena), concepto(alReves))),
                    HttpStatus.OK,
                    funcionario("CAJERO")
                )
            )
        assertFalse(vista["cobrable"].asBoolean())
        assertEquals(listOf(buena), vista["lineas"].toList().map { it["codigo"].asString() })
        assertTrue(vista["motivos"].single().asString().contains("termina antes de empezar"), vista.toString())

        // y el cobro la rechaza con 409, sin emitir nada
        val caja = nuevaCaja()
        send("POST", TASAS, cobro(caja, concepto(alReves)), HttpStatus.CONFLICT, funcionario("CAJERO"))
        assertEquals(0, registros("recibo", "caja" to caja.id).size)
    }

    // GET /api/caja/tasas

    @Test
    fun `las tasas vigentes a una fecha, por defecto hoy, con su precio y su fecha`() {
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "10.00", hoy.minusDays(60), hoy.minusDays(1))
        nuevaTasa(codigo, "12.30", hoy, descripcion = "CONSTANCIA DE NO ADEUDO")
        val futura = codigoDeTasa()
        nuevaTasa(futura, "5.00", hoy.plusDays(10))

        // CAJERO y SUPERVISOR_CAJA leen tasa
        listOf("CAJERO", "SUPERVISOR_CAJA").forEach { rol ->
            val hoyLista = lista("/api/caja/tasas", funcionario(rol))
            val tasa = hoyLista.single { it["codigo"].asString() == codigo }
            assertEquals("CONSTANCIA DE NO ADEUDO", tasa["descripcion"].asString())
            assertEquals("1.3.1.1.1.1", tasa["partida_presupuestal"].asString())
            assertTrue(tasa["area"].asString().startsWith("A-"), tasa.toString())
            assertEquals("12.30", tasa["precio"]["importe"].asString())
            assertEquals(hoy.toString(), tasa["precio"]["actualizado_a"].asString())
            assertTrue(hoyLista.none { it["codigo"].asString() == futura })
        }

        val ayer = lista("/api/caja/tasas?vigentes_a=${hoy.minusDays(1)}", funcionario("CAJERO"))
        assertEquals("10.00", ayer.single { it["codigo"].asString() == codigo }["precio"]["importe"].asString())
        assertEquals(hoy.minusDays(1).toString(), ayer.single { it["codigo"].asString() == codigo }["precio"]["actualizado_a"].asString())
        val luego = lista("/api/caja/tasas?vigentes_a=${hoy.plusDays(10)}", funcionario("CAJERO"))
        assertEquals("5.00", luego.single { it["codigo"].asString() == futura }["precio"]["importe"].asString())

        rejected("GET", "/api/caja/tasas?vigentes_a=ayer", null, "vigentes_a")
    }

    private fun lista(
        ruta: String,
        token: String
    ): List<JsonNode> = tree(send("GET", ruta, null, HttpStatus.OK, token)).toList()

    // el cuerpo de un cobro de tasas en efectivo, sin pagador, con esos conceptos en esa caja
    private fun cobro(
        caja: CajaDePrueba,
        vararg conceptos: Map<String, Any?>
    ): Map<String, Any?> =
        mapOf(
            "caja" to caja.codigo,
            "forma_pago" to "EFECTIVO",
            "conceptos" to conceptos.toList(),
            "observacion" to "cobro de tasas en ventanilla"
        )

    private fun concepto(
        codigo: String,
        cantidad: Int = 1
    ) = mapOf("codigo" to codigo, "cantidad" to cantidad)

    private fun cobroDeOrdenes(
        caja: CajaDePrueba,
        vararg ordenes: String
    ): Map<String, Any?> = mapOf("caja" to caja.codigo, "forma_pago" to "EFECTIVO", "ordenes" to ordenes.toList(), "observacion" to "cobro en ventanilla")

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val COBROS = "/api/caja/cobros"
        const val TASAS = "/api/caja/cobros/tasas"
    }
}
