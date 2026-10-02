package caja.cobro

import caja.CajaApiTest
import caja.comun.LIMA
import caja.emision.texto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// POST /api/caja/cobros: el turno, el número, el recibo, las órdenes PAGADA y el evento en el buzón, en una sola
// transacción. y el original del recibo en pdf
class CobroApiTest : CajaApiTest() {
    private val hoy: LocalDate get() = LocalDate.now(LIMA)

    @Test
    fun `el cobro emite el 001-0000001, paga la orden, abre el turno y deja el evento pendiente`() {
        // la única caja de la serie 001 en la corrida: la base se borra al empezar
        val caja = nuevaCaja(serie = "001")
        val cajero = cuenta("CAJERO")
        val orden = post(ORDENES, orden())
        val ordenId = orden["orden_id"].asString()

        val cobro = post(COBROS, cobro(caja, ordenId), cajero.token)

        val recibo = cobro["recibo"]
        assertEquals("001-0000001", recibo["numero_impreso"].asString())
        assertEquals("001", recibo["serie"].asString())
        assertEquals(1L, recibo["numero"].asLong())
        assertEquals(cajero.email, recibo["cajero"].asString())
        assertEquals("EFECTIVO", recibo["forma_pago"].asString())
        assertEquals("NORMAL", recibo["tipo_pago"].asString())
        assertNotNull(recibo["emitido_en"].asString())
        assertEquals("150.50", recibo["total"]["importe"].asString())
        assertEquals(hoy.toString(), recibo["total"]["actualizado_a"].asString())
        assertEquals(1, recibo["lineas"].size())
        val linea = recibo["lineas"][0]
        assertEquals(ordenId, linea["orden_id"].asString())
        assertEquals("IMPUESTO PREDIAL 2026 - CUOTA 1", linea["concepto"].asString())
        assertEquals("150.50", linea["monto"]["importe"].asString())
        assertEquals("EN_TRANSITO", cobro["estado_del_pago"].asString())
        assertTrue(cobro["emitido"].asBoolean())
        val pagoId = UUID.fromString(cobro["pago_id"].asString()).toString()

        // lo guardado, leído como admin
        val guardado = registros("recibo", "numero_impreso" to "001-0000001").single()
        val reciboId = guardado["id"].asString()
        assertEquals(caja.id, guardado["attributes"]["caja"].asString())
        assertEquals(hoy.toString(), guardado["attributes"]["actualizado_a"].asString())
        val pagada = tree(send("GET", "/api/objects/orden_de_cobro/records/$ordenId", null, HttpStatus.OK))["attributes"]
        assertEquals("PAGADA", pagada["estado"].asString())
        assertEquals(reciboId, pagada["recibo"].asString())

        val turno = registros("turno", "clave_turno" to "${caja.id}|${cajero.email}|$hoy").single()
        assertEquals(guardado["attributes"]["turno"].asString(), turno["id"].asString())
        assertEquals(caja.id, turno["attributes"]["caja"].asString())
        assertEquals(hoy.toString(), turno["attributes"]["fecha"].asString())

        val lineas = registros("linea_recibo", "recibo" to reciboId)
        assertEquals(listOf(ordenId), lineas.map { it["attributes"]["orden"].asString() })
        assertEquals("rentas", lineas.single()["attributes"]["sistema_origen"].asString())

        val evento = registros("pago_evento", "evento_id" to pagoId).single()["attributes"]
        assertEquals("PAGO_REGISTRADO", evento["tipo"].asString())
        assertEquals("PENDIENTE", evento["estado"].asString())
        assertEquals(0L, evento["intentos"].asLong())
        assertEquals("rentas", evento["sistema_destino"].asString())
        assertEquals(reciboId, evento["recibo"].asString())
        assertEquals(turno["id"].asString(), evento["turno"].asString())
        val cuerpo = tree(evento["cuerpo"].asString())
        assertEquals(pagoId, cuerpo["pagoId"].asString())
        assertEquals("001-0000001", cuerpo["recibo"]["numero"].asString())
        assertEquals(ordenId, cuerpo["ordenes"][0]["ordenId"].asString())
        assertEquals("150.50", cuerpo["total"].asString())
    }

    @Test
    fun `el recibo trae el pagador tal como quedo guardado, en el cobro, el reenvio y la ficha`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        // el pagador de la orden, que el recibo congela
        val ordenId =
            post(
                ORDENES,
                orden("pagador_documento" to "  ab-1234 ", "pagador_nombre" to "QUISPE MAMANI, ROSA", "pagador_externo_id" to "77")
            )["orden_id"].asString()
        val clave = mapOf("Idempotency-Key" to UUID.randomUUID().toString())

        val cobro = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.CREATED, cajero.token, clave))["recibo"]
        val reenvio = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.OK, cajero.token, clave))["recibo"]
        val ficha = tree(send("GET", "/api/caja/recibos/${cobro["numero_impreso"].asString()}", null, HttpStatus.OK, cajero.token))

        val guardado = registros("recibo", "numero_impreso" to cobro["numero_impreso"].asString()).single()["attributes"]
        listOf(cobro, reenvio, ficha).forEach { recibo ->
            assertEquals(guardado["pagador_documento"].asString(), recibo["pagador_documento"].asString(), recibo.toString())
            assertEquals("QUISPE MAMANI, ROSA", recibo["pagador_nombre"].asString(), recibo.toString())
            assertEquals(77L, recibo["pagador_externo_id"].asLong(), recibo.toString())
        }
        assertEquals("AB-1234", cobro["pagador_documento"].asString())
    }

    @Test
    fun `el reenvio de un cobro cuyo recibo se anulo da 409 y no lo devuelve como cobrado`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        val clave = mapOf("Idempotency-Key" to UUID.randomUUID().toString())
        val numero = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.CREATED, cajero.token, clave))["recibo"]["numero_impreso"].asString()
        post(
            "/api/caja/recibos/$numero/anulacion",
            mapOf("motivo" to "COBRO EN DEMASÍA", "observacion" to "el pagador pagó dos veces en ventanilla"),
            funcionario("SUPERVISOR_CAJA")
        )

        val problema = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.CONFLICT, cajero.token, clave))

        assertTrue(problema["detail"].asString().contains("el recibo de ese cobro está anulado"), problema.toString())
        assertTrue(problema["detail"].asString().contains(numero), problema.toString())
        // no se cobró otra vez: la orden sigue PENDIENTE y hay un solo recibo
        assertEquals("PENDIENTE", estadoDe(ordenId))
        assertEquals(1, registros("recibo", "caja" to caja.id).size)
    }

    @Test
    fun `el segundo cobro de la misma orden da 409 y no emite otro recibo`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        post(COBROS, cobro(caja, ordenId), cajero.token)

        val problema = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.CONFLICT, cajero.token))
        assertTrue(problema["detail"].asString().contains(ordenId), problema.toString())
        assertEquals(1, registros("linea_recibo", "orden" to ordenId).size)
    }

    @Test
    fun `la misma Idempotency-Key devuelve el mismo recibo y el mismo pago_id sin cobrar otra vez`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        val clave = mapOf("Idempotency-Key" to UUID.randomUUID().toString())

        val primero = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.CREATED, cajero.token, clave))
        val segundo = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.OK, cajero.token, clave))

        assertTrue(primero["emitido"].asBoolean())
        assertEquals(false, segundo["emitido"].asBoolean())
        assertEquals(primero["recibo"]["numero_impreso"].asString(), segundo["recibo"]["numero_impreso"].asString())
        assertEquals(primero["pago_id"].asString(), segundo["pago_id"].asString())
        assertEquals(primero["recibo"]["lineas"].toString(), segundo["recibo"]["lineas"].toString())
        val recibos = registros("recibo", "caja" to caja.id)
        assertEquals(1, recibos.size)
        assertEquals(1, registros("pago_evento", "recibo" to recibos.single()["id"].asString()).size)

        rejected("POST", COBROS, cobro(caja, ordenId), "Idempotency-Key", cajero.token, mapOf("Idempotency-Key" to "x".repeat(65)))

        // la clave es del cajero que la mandó: otro cajero con la misma clave no recibe ese recibo, choca
        val otraOrden = post(ORDENES, orden())["orden_id"].asString()
        send("POST", COBROS, cobro(caja, otraOrden), HttpStatus.CONFLICT, funcionario("CAJERO"), clave)
        assertEquals(1, registros("recibo", "caja" to caja.id).size)
        assertEquals("PENDIENTE", estadoDe(otraOrden))
    }

    @Test
    fun `ordenes de dos sistemas no caben en un recibo`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val rentas = post(ORDENES, orden())["orden_id"].asString()
        val mercados = post(ORDENES, orden("sistema_origen" to "mercados"))["orden_id"].asString()

        rejected("POST", COBROS, cobro(caja, rentas, mercados), "ordenes", cajero.token)
        assertEquals(0, registros("recibo", "caja" to caja.id).size)
        assertEquals("PENDIENTE", estadoDe(rentas))
    }

    @Test
    fun `otro cajero en el cuerpo da 403 y otra fecha de pago da 400`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val ordenId = post(ORDENES, orden())["orden_id"].asString()

        val problema = tree(send("POST", COBROS, cobro(caja, ordenId) + ("cajero" to "otro@caja.test"), HttpStatus.FORBIDDEN, cajero.token))
        assertTrue(problema["detail"].asString().contains("otro@caja.test"), problema.toString())
        rejected("POST", COBROS, cobro(caja, ordenId) + ("fecha_de_pago" to hoy.minusDays(1).toString()), "fecha_de_pago", cajero.token)
        // el suyo y hoy, dichos en el cuerpo, valen
        post(COBROS, cobro(caja, ordenId) + ("cajero" to cajero.email) + ("fecha_de_pago" to hoy.toString()), cajero.token)
    }

    @Test
    fun `lo que no se puede cobrar se dice con su codigo`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val ordenId = post(ORDENES, orden())["orden_id"].asString()

        // la misma orden dos veces, ninguna, un id que no es uuid, sin observación: 400
        rejected("POST", COBROS, cobro(caja, ordenId, ordenId), "ordenes", cajero.token)
        rejected("POST", COBROS, cobro(caja), "ordenes", cajero.token)
        rejected("POST", COBROS, cobro(caja, "12"), "ordenes", cajero.token)
        rejected("POST", COBROS, cobro(caja, ordenId) + ("forma_pago" to "BITCOIN"), "forma_pago", cajero.token)
        rejected("POST", COBROS, cobro(caja, ordenId) - "observacion", "observacion", cajero.token)
        rejected("POST", COBROS, cobro(caja, ordenId) + ("tributo" to "PREDIAL"), "tributo", cajero.token)
        // una orden que no existe o una caja que no existe: 404
        send("POST", COBROS, cobro(caja, UUID.randomUUID().toString()), HttpStatus.NOT_FOUND, cajero.token)
        send("POST", COBROS, cobro(caja, ordenId) + ("caja" to "C-NO-EXISTE"), HttpStatus.NOT_FOUND, cajero.token)
        // una caja de baja, o una orden que todavía no es exigible: 409
        send("POST", COBROS, cobro(nuevaCaja(activa = false), ordenId), HttpStatus.CONFLICT, cajero.token)
        val futura = post(ORDENES, orden("fecha_exigibilidad" to hoy.plusDays(1).toString()))["orden_id"].asString()
        val problema = tree(send("POST", COBROS, cobro(caja, futura), HttpStatus.CONFLICT, cajero.token))
        assertTrue(problema["detail"].asString().contains(futura), problema.toString())

        assertEquals("PENDIENTE", estadoDe(ordenId))
        assertEquals(0, registros("recibo", "caja" to caja.id).size)
    }

    @Test
    fun `diez cobros simultaneos de la misma orden dan un solo recibo y nueve 409`() {
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        // diez cajas y diez cajeros: ni el turno ni la serie los ordenan, solo el candado de la orden (CajaJdbcTest)
        val ventanillas = (1..10).map { nuevaCaja() to cuenta("CAJERO") }

        val respuestas = simultaneas(ventanillas.map { (caja, cajero) -> { exchange("POST", COBROS, cobro(caja, ordenId), cajero.token) } })

        val estados = respuestas.map { it.first }
        assertEquals(1, estados.count { it == HttpStatus.CREATED }, respuestas.toString())
        assertEquals(9, estados.count { it == HttpStatus.CONFLICT }, respuestas.toString())
        val lineas = registros("linea_recibo", "orden" to ordenId)
        assertEquals(1, lineas.size)
        assertEquals(1, registros("pago_evento", "recibo" to lineas.single()["attributes"]["recibo"].asString()).size)
        assertEquals("PAGADA", estadoDe(ordenId))
    }

    @Test
    fun `veinte cobros simultaneos en la misma caja numeran del 1 al 20 sin huecos ni repetidos`() {
        val caja = nuevaCaja()
        // diez cajeros, dos cobros a la vez cada uno: el primer cobro del día de cada uno abre su turno, una vez
        val cajeros = (1..10).map { cuenta("CAJERO") }
        val cobros = cajeros.flatMap { cajero -> (1..2).map { cajero to post(ORDENES, orden())["orden_id"].asString() } }

        val respuestas = simultaneas(cobros.map { (cajero, ordenId) -> { exchange("POST", COBROS, cobro(caja, ordenId), cajero.token) } })

        assertTrue(respuestas.all { it.first == HttpStatus.CREATED }, respuestas.toString())
        val numeros = respuestas.map { tree(it.second)["recibo"]["numero"].asLong() }
        assertEquals((1L..20L).toList(), numeros.sorted())
        assertEquals((1L..20L).toList(), registros("recibo", "caja" to caja.id).map { it["attributes"]["numero"].asLong() }.sorted())
        assertEquals(10, registros("turno", "caja" to caja.id).size)
    }

    @Test
    fun `dos cobros simultaneos con la misma Idempotency-Key en la misma caja emiten un solo recibo`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        val clave = mapOf("Idempotency-Key" to UUID.randomUUID().toString())

        val respuestas = simultaneas((1..2).map { { exchange("POST", COBROS, cobro(caja, ordenId), cajero.token, clave) } })

        // uno emite; el otro recibe ese mismo recibo (200) o un 409, nunca un segundo recibo
        val estados = respuestas.map { it.first }
        assertEquals(1, estados.count { it == HttpStatus.CREATED }, respuestas.toString())
        assertTrue(estados.all { it in setOf(HttpStatus.CREATED, HttpStatus.OK, HttpStatus.CONFLICT) }, respuestas.toString())
        val emitido = tree(respuestas.first { it.first == HttpStatus.CREATED }.second)
        respuestas.filter { it.first == HttpStatus.OK }.forEach {
            assertEquals(emitido["recibo"]["numero_impreso"].asString(), tree(it.second)["recibo"]["numero_impreso"].asString())
        }
        assertEquals(1, registros("recibo", "caja" to caja.id).size)
        assertEquals(1, registros("linea_recibo", "orden" to ordenId).size)
    }

    @Test
    fun `un CAJERO cobra, TESORERIA y SISTEMA_ORIGEN reciben 403`() {
        val caja = nuevaCaja()
        val ordenId = post(ORDENES, orden())["orden_id"].asString()

        listOf("TESORERIA", "SISTEMA_ORIGEN").forEach { rol ->
            val problema = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.FORBIDDEN, funcionario(rol)))
            assertTrue(problema["detail"].asString().contains("recibo"), problema.toString())
        }
        assertEquals("PENDIENTE", estadoDe(ordenId))
        post(COBROS, cobro(caja, ordenId), funcionario("CAJERO"))
        assertEquals("PAGADA", estadoDe(ordenId))
    }

    @Test
    fun `el original en pdf lo obtiene el cajero que cobro, otro cajero recibe 409`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val ordenId = post(ORDENES, orden("pagador_nombre" to "PEÑA ÑAUPARI, JOSÉ"))["orden_id"].asString()
        val numero = post(COBROS, cobro(caja, ordenId), cajero.token)["recibo"]["numero_impreso"].asString()

        val pdf =
            client
                .get()
                .uri("/api/caja/recibos/$numero/pdf")
                .header(HttpHeaders.AUTHORIZATION, cajero.token)
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .contentType(MediaType.APPLICATION_PDF)
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody!!
        val texto = texto(pdf)
        listOf(MUNICIPALIDAD, numero, caja.codigo, cajero.email, "PEÑA ÑAUPARI, JOSÉ", "S/ 150.50", "Importes actualizados al").forEach {
            assertTrue(it in texto, "falta «$it» en:\n$texto")
        }

        val otro = funcionario("CAJERO")
        val problema = tree(send("GET", "/api/caja/recibos/$numero/pdf", null, HttpStatus.CONFLICT, otro))
        assertTrue(problema["detail"].asString().contains("duplicado"), problema.toString())
        send("GET", "/api/caja/recibos/X-0000001/pdf", null, HttpStatus.NOT_FOUND, cajero.token)
    }

    // el cuerpo de un cobro en efectivo de esas órdenes en esa caja
    private fun cobro(
        caja: CajaDePrueba,
        vararg ordenes: String
    ): Map<String, Any?> =
        mapOf(
            "caja" to caja.codigo,
            "forma_pago" to "EFECTIVO",
            "ordenes" to ordenes.toList(),
            "observacion" to "cobro en ventanilla"
        )

    private fun estadoDe(orden: String): String =
        tree(send("GET", "/api/objects/orden_de_cobro/records/$orden", null, HttpStatus.OK))["attributes"]["estado"].asString()

    // las llamadas a la vez, cada una en su hilo, soltadas juntas
    private fun simultaneas(llamadas: List<() -> Pair<HttpStatus, String>>): List<Pair<HttpStatus, String>> {
        val salida = CyclicBarrier(llamadas.size)
        val hilos = Executors.newFixedThreadPool(llamadas.size)
        return try {
            llamadas
                .map { llamada ->
                    hilos.submit<Pair<HttpStatus, String>> {
                        salida.await(30, TimeUnit.SECONDS)
                        llamada()
                    }
                }.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            hilos.shutdownNow()
        }
    }

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val COBROS = "/api/caja/cobros"
    }
}
