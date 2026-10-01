package caja.cobro

import caja.CajaApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// POST y GET /api/caja/ordenes-de-cobro: el alta que manda el sistema de origen y la lista de la ventanilla
class OrdenesApiTest : CajaApiTest() {
    @Test
    fun `el alta da 201 y la segunda da 200 con el mismo orden_id`() {
        val cuerpo = orden()
        val alta = post(ORDENES, cuerpo)
        assertTrue(alta["nueva"].asBoolean())
        assertEquals("PENDIENTE", alta["estado"].asString())
        assertEquals("150.50", alta["importe"]["importe"].asString())
        assertEquals("2026-03-15", alta["importe"]["actualizado_a"].asString())
        assertEquals("2026-02-28", alta["fecha_exigibilidad"].asString())
        assertEquals("rentas", alta["sistema_origen"].asString())
        assertEquals(cuerpo["referencia_externa"], alta["referencia_externa"].asString())
        assertEquals(1234L, alta["pagador_externo_id"].asLong())

        // el reintento devuelve la que ya estaba, como estaba: la caja no la rehace con lo que traiga
        val reintento = tree(send("POST", ORDENES, cuerpo + ("importe" to "999.00"), HttpStatus.OK))
        assertEquals(alta["orden_id"].asString(), reintento["orden_id"].asString())
        assertEquals(false, reintento["nueva"].asBoolean())
        assertEquals("150.50", reintento["importe"]["importe"].asString())
    }

    @Test
    fun `el sistema de origen se normaliza antes de formar la clave`() {
        val cuerpo = orden()
        val alta = post(ORDENES, cuerpo)
        val otra = tree(send("POST", ORDENES, cuerpo + ("sistema_origen" to " RENTAS "), HttpStatus.OK))
        assertEquals(alta["orden_id"].asString(), otra["orden_id"].asString())
    }

    @Test
    fun `la misma referencia de otro sistema es otra orden`() {
        val cuerpo = orden()
        val rentas = post(ORDENES, cuerpo)
        val mercados = post(ORDENES, cuerpo + ("sistema_origen" to "mercados"))
        assertTrue(rentas["orden_id"].asString() != mercados["orden_id"].asString())
    }

    @Test
    fun `diez altas simultaneas de la misma clave dan una sola orden`() {
        val cuerpo = orden()
        val altas = 10
        val salida = CyclicBarrier(altas)
        val hilos = Executors.newFixedThreadPool(altas)
        val respuestas =
            try {
                (1..altas)
                    .map {
                        hilos.submit<Pair<HttpStatus, String>> {
                            salida.await(30, TimeUnit.SECONDS)
                            exchange("POST", ORDENES, cuerpo)
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                hilos.shutdownNow()
            }

        val estados = respuestas.map { it.first }
        assertTrue(estados.all { it == HttpStatus.CREATED || it == HttpStatus.OK }, respuestas.toString())
        assertEquals(1, estados.count { it == HttpStatus.CREATED }, estados.toString())
        assertEquals(1, respuestas.map { tree(it.second)["orden_id"].asString() }.toSet().size, respuestas.toString())
        val guardadas = tree(send("GET", "/api/objects/orden_de_cobro/records?clave_origen=rentas|${cuerpo["referencia_externa"]}", null, HttpStatus.OK))
        assertEquals(1, guardadas["totalElements"].asInt(), guardadas.toString())
    }

    @Test
    fun `un importe de cero, negativo o con tres decimales da 400 en importe`() {
        listOf("0", "-5.00", "10.005", "1e3", "diez").forEach { rejected("POST", ORDENES, orden("importe" to it), "importe") }
    }

    @Test
    fun `un sistema de origen invalido da 400 en sistema_origen`() {
        listOf("ren tas", "rentás", "", "x".repeat(21)).forEach { rejected("POST", ORDENES, orden("sistema_origen" to it), "sistema_origen") }
    }

    @Test
    fun `una observacion de cuatro caracteres da 400 en observacion`() {
        rejected("POST", ORDENES, orden("observacion" to "abcd"), "observacion")
        rejected("POST", ORDENES, orden() - "observacion", "observacion")
    }

    @Test
    fun `una fecha que no es ISO da 400 en su campo`() {
        rejected("POST", ORDENES, orden("fecha_exigibilidad" to "28/02/2026"), "fecha_exigibilidad")
        rejected("POST", ORDENES, orden() - "actualizado_a", "actualizado_a")
    }

    @Test
    fun `un tributo en el cuerpo da 400`() {
        rejected("POST", ORDENES, orden("tributo" to "PREDIAL"), "tributo")
        rejected("POST", ORDENES, orden("ejercicio" to 2026), "ejercicio")
        rejected("POST", ORDENES, orden("periodo" to "01"), "periodo")
    }

    @Test
    fun `la lista de pendientes filtra por pagador_documento`() {
        val documento = "D${unico()}"
        val primera = post(ORDENES, orden("pagador_documento" to documento.lowercase(), "fecha_exigibilidad" to "2026-04-30"))
        val segunda = post(ORDENES, orden("pagador_documento" to documento, "fecha_exigibilidad" to "2026-01-31"))
        post(ORDENES, orden())

        val lista = tree(send("GET", "$ORDENES?pagador_documento=${documento.lowercase()}", null, HttpStatus.OK))
        assertEquals(2, lista["totalElements"].asInt(), lista.toString())
        // la más antigua primero: se cobra por fecha de exigibilidad
        assertEquals(
            listOf(segunda["orden_id"].asString(), primera["orden_id"].asString()),
            lista["content"]
                .iterator()
                .asSequence()
                .map { it["orden_id"].asString() }
                .toList()
        )
        assertTrue(lista["content"].iterator().asSequence().all { it["pagador_documento"].asString() == documento && it["estado"].asString() == "PENDIENTE" })
        assertTrue(lista["content"].iterator().asSequence().all { it["importe"]["actualizado_a"].asString() == "2026-03-15" })

        val pagadas = tree(send("GET", "$ORDENES?pagador_documento=$documento&estado=PAGADA", null, HttpStatus.OK))
        assertEquals(0, pagadas["totalElements"].asInt())
        rejected("GET", "$ORDENES?estado=VENCIDA", null, "estado")
    }

    @Test
    fun `un usuario CAJERO no puede dar de alta y uno SISTEMA_ORIGEN si`() {
        val cajero = funcionario("CAJERO")
        client
            .post()
            .uri(ORDENES)
            .header("Authorization", cajero)
            .bodyValue(orden())
            .exchange()
            .expectStatus()
            .isForbidden
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)

        val sistema = funcionario("SISTEMA_ORIGEN")
        val cuerpo = orden()
        val alta = post(ORDENES, cuerpo, sistema)
        // el reintento relee la que ya estaba: también puede
        assertEquals(alta["orden_id"].asString(), tree(send("POST", ORDENES, cuerpo, HttpStatus.OK, sistema))["orden_id"].asString())

        // la ventanilla lee
        val lista = tree(send("GET", "$ORDENES?pagador_documento=${cuerpo["pagador_documento"]}", null, HttpStatus.OK, cajero))
        assertEquals(alta["orden_id"].asString(), lista["content"][0]["orden_id"].asString())
    }

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
    }
}
