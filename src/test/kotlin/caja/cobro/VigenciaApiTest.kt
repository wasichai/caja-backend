package caja.cobro

import caja.CajaApiTest
import caja.buzon.SistemaDeOrigenFalso
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId

// la comprobación en línea antes de cobrar (caja-backend#42): con caja.buzon.destinos.<sistema>.verificar, la ventanilla
// le pregunta al origen si las órdenes siguen vigentes y con el mismo importe, fuera de toda transacción. una que el
// origen ya no cobra queda ANULADA con su motivo y el cobro es un 422; con el origen caído, la configuración decide
// (sin-respuesta: COBRAR deja la advertencia en el recibo, NO_COBRAR es un 503). el reenvío de un cobro ya emitido no
// vuelve a preguntar. el buzón está apagado: aquí no se entrega nada, y el contexto se cierra con la clase
@DirtiesContext
class VigenciaApiTest : CajaApiTest() {
    @Test
    fun `todo vigente cobra`() {
        val altas = altas(VERIFICA, "12.50", "40.00")
        altas.forEach { origen.vigencia(it.referencia, vigente = true, importe = it.importe) }

        val cobro = tree(send("POST", COBROS, cobro(nuevaCaja(), altas), HttpStatus.CREATED, cuenta("CAJERO").token))

        assertTrue(cobro["emitido"].asBoolean())
        val preguntas = origen.preguntasPor(altas.first().referencia)
        assertEquals(1, preguntas.size, preguntas.toString())
        val pregunta = json.readTree(preguntas.single())["ordenes"].toList()
        assertEquals(altas.map { it.referencia to it.importe }, pregunta.map { it["referencia_externa"].asString() to it["importe"].asString() })
        assertTrue(cobro["recibo"]["advertencia_origen"]?.isNull ?: true, cobro.toString())
        altas.forEach { assertEquals("PAGADA", estadoDe(it.id)) }
    }

    @Test
    fun `una no vigente da 422 y la orden queda ANULADA con el motivo del origen`() {
        val (vigente, retirada) = altas(VERIFICA, "12.50", "40.00")
        origen.vigencia(vigente.referencia, vigente = true, importe = vigente.importe)
        origen.vigencia(retirada.referencia, vigente = false, motivo = "Redeterminada por la DJ n° 12")

        val problema = tree(send("POST", COBROS, cobro(nuevaCaja(), listOf(vigente, retirada)), HttpStatus.UNPROCESSABLE_CONTENT, cuenta("CAJERO").token))

        assertTrue(problema["detail"].asString().contains("el sistema que la emitió la retiró"), problema.toString())
        assertEquals("ANULADA", estadoDe(retirada.id))
        assertEquals("el origen dice: Redeterminada por la DJ n° 12", orden(retirada.id)["motivo_retiro"].asString())
        assertEquals("PENDIENTE", estadoDe(vigente.id), "la vigente no se cobró: no se emitió nada")
        assertEquals(0, lineasDe(vigente.id))
    }

    @Test
    fun `otro importe da 422 y la orden queda ANULADA con el importe del origen`() {
        val alta = altas(VERIFICA, "12.50").single()
        origen.vigencia(alta.referencia, vigente = true, importe = "13.00")

        send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.UNPROCESSABLE_CONTENT, cuenta("CAJERO").token)

        assertEquals("ANULADA", estadoDe(alta.id))
        assertEquals("el origen dice otro importe: 13.00", orden(alta.id)["motivo_retiro"].asString())
        assertEquals(0, lineasDe(alta.id))
    }

    @Test
    fun `origen caido y sin-respuesta COBRAR cobra con la advertencia, que aparece en cobros sin verificar`() {
        val alta = altas(CAIDO_COBRA, "12.50").single()

        val cobro = tree(send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.CREATED, cuenta("CAJERO").token))

        val advertencia = cobro["recibo"]["advertencia_origen"].asString()
        assertTrue(advertencia.contains(CAIDO_COBRA), advertencia)
        assertEquals("PAGADA", estadoDe(alta.id))
        val numero = cobro["recibo"]["numero_impreso"].asString()
        val sinVerificar = tree(send("GET", "$SIN_VERIFICAR?fecha=${hoy()}", null, HttpStatus.OK, funcionario("SUPERVISOR_CAJA")))
        val fila = sinVerificar.toList().single { it["numero_impreso"].asString() == numero }
        assertEquals(advertencia, fila["advertencia_origen"].asString())
        assertEquals("12.50", fila["total"]["importe"].asString())
        assertFalse(sinVerificar.toList().any { it["advertencia_origen"].isNull }, sinVerificar.toString())
    }

    @Test
    fun `origen caido y sin-respuesta NO_COBRAR es 503 y no se emite nada`() {
        val alta = altas(CAIDO_NO_COBRA, "12.50").single()

        val problema = tree(send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.SERVICE_UNAVAILABLE, cuenta("CAJERO").token))

        assertTrue(problema["detail"].asString().contains(CAIDO_NO_COBRA), problema.toString())
        assertEquals("PENDIENTE", estadoDe(alta.id))
        assertEquals(0, lineasDe(alta.id))
    }

    @Test
    fun `verificar false no pregunta`() {
        val alta = altas(NO_VERIFICA, "12.50").single()
        origen.vigencia(alta.referencia, vigente = false, motivo = "no se debería preguntar")

        send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.CREATED, cuenta("CAJERO").token)

        assertEquals(emptyList<String>(), origen.preguntasPor(alta.referencia))
        assertEquals("PAGADA", estadoDe(alta.id))
    }

    @Test
    fun `sin destino configurado no pregunta`() {
        val alta = altas("vig-sin-destino", "12.50").single()

        send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.CREATED, cuenta("CAJERO").token)

        assertEquals("PAGADA", estadoDe(alta.id))
    }

    @Test
    fun `el reenvio con la misma Idempotency-Key no vuelve a consultar`() {
        val alta = altas(VERIFICA, "12.50").single()
        origen.vigencia(alta.referencia, vigente = true, importe = alta.importe)
        val cajero = cuenta("CAJERO").token
        val caja = nuevaCaja()
        val clave = mapOf("Idempotency-Key" to "vig-${unico()}")
        val primero = tree(send("POST", COBROS, cobro(caja, listOf(alta)), HttpStatus.CREATED, cajero, clave))
        // después el origen la redetermina: el reenvío devuelve el recibo de la primera vez, sin volver a preguntar
        origen.vigencia(alta.referencia, vigente = false, motivo = "Redeterminada después del cobro")

        val reenvio = tree(send("POST", COBROS, cobro(caja, listOf(alta)), HttpStatus.OK, cajero, clave))

        assertFalse(reenvio["emitido"].asBoolean())
        assertEquals(primero["recibo"]["numero_impreso"].asString(), reenvio["recibo"]["numero_impreso"].asString())
        assertEquals(1, origen.preguntasPor(alta.referencia).size)
        assertEquals("PAGADA", estadoDe(alta.id))
    }

    @Test
    fun `una respuesta ilegible cuenta como origen caido`() {
        val alta = altas(ILEGIBLE, "12.50").single()

        send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.SERVICE_UNAVAILABLE, cuenta("CAJERO").token)

        assertEquals(1, origen.preguntasPor(alta.referencia).size)
        assertEquals("PENDIENTE", estadoDe(alta.id))
    }

    @Test
    fun `una referencia que el origen calla cuenta como origen caido`() {
        val alta = altas(VERIFICA_NO_COBRA, "12.50").single()

        send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.SERVICE_UNAVAILABLE, cuenta("CAJERO").token)

        assertEquals("PENDIENTE", estadoDe(alta.id))
    }

    @Test
    fun `una orden que no se puede cobrar no se pregunta`() {
        val alta = altas(VERIFICA, "12.50").single()
        origen.vigencia(alta.referencia, vigente = true, importe = alta.importe)
        val cajero = cuenta("CAJERO").token
        send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.CREATED, cajero)

        send("POST", COBROS, cobro(nuevaCaja(), listOf(alta)), HttpStatus.CONFLICT, cajero)

        assertEquals(1, origen.preguntasPor(alta.referencia).size)
    }

    @Test
    fun `cobros sin verificar pide READ sobre el recibo y una fecha valida`() {
        send("GET", "$SIN_VERIFICAR?fecha=${hoy()}", null, HttpStatus.FORBIDDEN, funcionario(listOf(permiso("caja", "READ"))))
        rejected("GET", "$SIN_VERIFICAR?fecha=ayer", null, "fecha")
    }

    private data class Alta(
        val id: String,
        val referencia: String,
        val importe: String
    )

    // órdenes de ese sistema dadas de alta por el admin, con esos importes
    private fun altas(
        sistema: String,
        vararg importes: String
    ): List<Alta> =
        importes.map { importe ->
            val alta = post(ORDENES, orden("sistema_origen" to sistema, "importe" to importe))
            Alta(alta["orden_id"].asString(), alta["referencia_externa"].asString(), importe)
        }

    private fun cobro(
        caja: CajaDePrueba,
        altas: List<Alta>
    ): Map<String, Any?> =
        mapOf(
            "caja" to caja.codigo,
            "forma_pago" to "EFECTIVO",
            "ordenes" to altas.map { it.id },
            "observacion" to "cobro en ventanilla"
        )

    private fun orden(id: String): JsonNode = tree(send("GET", "/api/objects/orden_de_cobro/records/$id", null, HttpStatus.OK))["attributes"]

    private fun estadoDe(id: String): String = orden(id)["estado"].asString()

    private fun lineasDe(orden: String): Int = registros("linea_recibo", "orden" to orden).size

    private fun hoy(): LocalDate = LocalDate.now(ZoneId.of("America/Lima"))

    companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val COBROS = "/api/caja/cobros"
        const val SIN_VERIFICAR = "/api/caja/cobros/sin-verificar"
        const val VERIFICA = "vig-verifica"
        const val VERIFICA_NO_COBRA = "vig-verifica-nc"
        const val NO_VERIFICA = "vig-no-verifica"
        const val CAIDO_COBRA = "vig-caido-cobra"
        const val CAIDO_NO_COBRA = "vig-caido-nc"
        const val ILEGIBLE = "vig-ilegible"

        val origen = SistemaDeOrigenFalso()

        @JvmStatic
        @DynamicPropertySource
        fun destinos(registro: DynamicPropertyRegistry) {
            registro.add("caja.buzon.destinos.$VERIFICA.url") { origen.url() }
            registro.add("caja.buzon.destinos.$VERIFICA.verificar") { "true" }
            registro.add("caja.buzon.destinos.$VERIFICA_NO_COBRA.url") { origen.url() }
            registro.add("caja.buzon.destinos.$VERIFICA_NO_COBRA.verificar") { "true" }
            registro.add("caja.buzon.destinos.$VERIFICA_NO_COBRA.sin-respuesta") { "NO_COBRAR" }
            registro.add("caja.buzon.destinos.$NO_VERIFICA.url") { origen.url() }
            // nadie escucha en el puerto 1: la conexión se rechaza
            registro.add("caja.buzon.destinos.$CAIDO_COBRA.url") { "http://127.0.0.1:1" }
            registro.add("caja.buzon.destinos.$CAIDO_COBRA.verificar") { "true" }
            registro.add("caja.buzon.destinos.$CAIDO_COBRA.sin-respuesta") { "COBRAR" }
            registro.add("caja.buzon.destinos.$CAIDO_NO_COBRA.url") { "http://127.0.0.1:1" }
            registro.add("caja.buzon.destinos.$CAIDO_NO_COBRA.verificar") { "true" }
            registro.add("caja.buzon.destinos.$CAIDO_NO_COBRA.sin-respuesta") { "NO_COBRAR" }
            registro.add("caja.buzon.destinos.$ILEGIBLE.url") { origen.url("ilegible") }
            registro.add("caja.buzon.destinos.$ILEGIBLE.verificar") { "true" }
            registro.add("caja.buzon.destinos.$ILEGIBLE.sin-respuesta") { "NO_COBRAR" }
        }

        @JvmStatic
        @AfterAll
        fun apagar() = origen.close()
    }
}
