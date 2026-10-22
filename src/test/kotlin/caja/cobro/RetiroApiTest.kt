package caja.cobro

import caja.CajaApiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// POST /api/caja/ordenes-de-cobro/retiro: el sistema de origen retira una orden que ya no se debe (la redeterminó, la
// anuló, la reemplazó). una PENDIENTE queda ANULADA con su motivo y su fecha; una PAGADA no se retira (409), y el
// cobro de una ANULADA es un 422 que lo dice
class RetiroApiTest : CajaApiTest() {
    @Test
    fun `retirar una PENDIENTE la deja ANULADA con su motivo y su fecha`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val alta = alta(rentas)

        val retirada = tree(send("POST", RETIRO, retiro(alta), HttpStatus.OK, rentas))

        assertEquals(alta["orden_id"].asString(), retirada["orden_id"].asString())
        assertEquals("ANULADA", retirada["estado"].asString())
        assertEquals(MOTIVO, retirada["motivo_retiro"].asString())
        assertNotNull(retirada["retirada_en"]?.takeUnless { it.isNull }, retirada.toString())
        assertEquals("ANULADA", estadoDe(alta["orden_id"].asString()))
        assertEquals(RAZON, razonDelUltimo(alta["orden_id"].asString(), "UPDATE"))
    }

    @Test
    fun `retirar dos veces es idempotente`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val alta = alta(rentas)
        val primero = tree(send("POST", RETIRO, retiro(alta), HttpStatus.OK, rentas))

        val segundo = tree(send("POST", RETIRO, retiro(alta, motivo = "otro motivo, más tarde"), HttpStatus.OK, rentas))

        assertEquals("ANULADA", segundo["estado"].asString())
        assertEquals(primero["retirada_en"].asString(), segundo["retirada_en"].asString())
        assertEquals(MOTIVO, segundo["motivo_retiro"].asString())
    }

    @Test
    fun `retirar una PAGADA es 409 y dice el recibo`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val alta = alta(rentas)
        val ordenId = alta["orden_id"].asString()
        post(COBROS, cobro(nuevaCaja(), ordenId), funcionario("CAJERO"))
        val recibo = tree(send("GET", "/api/objects/orden_de_cobro/records/$ordenId", null, HttpStatus.OK))["attributes"]["recibo"].asString()

        val problema = tree(send("POST", RETIRO, retiro(alta), HttpStatus.CONFLICT, rentas))

        assertTrue(problema["detail"].asString().contains(recibo), problema.toString())
        assertEquals("PAGADA", estadoDe(ordenId))
    }

    @Test
    fun `una referencia que no existe es 404`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val referencia = "ARB-p1|LIMP|2026|3|1|2"

        val problema = tree(send("POST", RETIRO, mapOf("referencia_externa" to referencia, "motivo" to MOTIVO), HttpStatus.NOT_FOUND, rentas))

        assertTrue(problema["detail"].asString().contains(referencia), problema.toString())
    }

    @Test
    fun `una referencia con barras se retira por el cuerpo`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val referencia = "ARB-p${unico()}|LIMP|2026|3|1|2"
        val alta = alta(rentas, referencia)

        val retirada = tree(send("POST", RETIRO, retiro(alta), HttpStatus.OK, rentas))

        assertEquals(referencia, retirada["referencia_externa"].asString())
        assertEquals("ANULADA", retirada["estado"].asString())
    }

    @Test
    fun `otro sistema no retira lo ajeno`() {
        val (rentasNombre, rentas) = cuentaDeServicio("rentas")
        val (_, mercados) = cuentaDeServicio("mercados")
        val alta = alta(rentas)

        // la cuenta de mercados no ve la orden de rentas: 404, igual que si no existiera
        send("POST", RETIRO, retiro(alta), HttpStatus.NOT_FOUND, mercados)
        // ni nombrando el sistema ajeno en el cuerpo: es un permiso
        send("POST", RETIRO, retiro(alta) + ("sistema_origen" to rentasNombre), HttpStatus.FORBIDDEN, mercados)
        // una persona con el rol SISTEMA_ORIGEN no retira
        send("POST", RETIRO, retiro(alta), HttpStatus.FORBIDDEN, funcionario("SISTEMA_ORIGEN"))
        // un ADMIN retira de contingencia, nombrando el sistema
        rejected("POST", RETIRO, retiro(alta), "sistema_origen")
        assertEquals("PENDIENTE", estadoDe(alta["orden_id"].asString()))
        val retirada = tree(send("POST", RETIRO, retiro(alta) + ("sistema_origen" to rentasNombre), HttpStatus.OK))
        assertEquals("ANULADA", retirada["estado"].asString())
    }

    @Test
    fun `sin motivo es 400 en motivo`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val alta = alta(rentas)

        rejected("POST", RETIRO, retiro(alta) - "motivo", "motivo", rentas)
        rejected("POST", RETIRO, retiro(alta, motivo = "   "), "motivo", rentas)
        rejected("POST", RETIRO, retiro(alta, motivo = "x".repeat(201)), "motivo", rentas)
        rejected("POST", RETIRO, retiro(alta, motivo = "con un\u0007timbre"), "motivo", rentas)
        rejected("POST", RETIRO, retiro(alta) - "referencia_externa", "referencia_externa", rentas)
        rejected("POST", RETIRO, retiro(alta) + ("importe" to "10.00"), "importe", rentas)
        assertEquals("PENDIENTE", estadoDe(alta["orden_id"].asString()))
    }

    @Test
    fun `retiro y cobro a la vez - gana uno`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        repeat(3) {
            val alta = alta(rentas)
            val ordenId = alta["orden_id"].asString()
            val caja = nuevaCaja()
            val cajero = funcionario("CAJERO")

            val (cobro, retiro) =
                runBlocking {
                    coroutineScope {
                        listOf(
                            async(Dispatchers.IO) { exchange("POST", COBROS, cobro(caja, ordenId), cajero) },
                            async(Dispatchers.IO) { exchange("POST", RETIRO, retiro(alta), rentas) }
                        ).awaitAll()
                    }
                }

            val estados = cobro.first to retiro.first
            assertTrue(
                estados == (HttpStatus.CREATED to HttpStatus.CONFLICT) || estados == (HttpStatus.UNPROCESSABLE_CONTENT to HttpStatus.OK),
                "cobro $cobro, retiro $retiro"
            )
            // nunca queda ANULADA con recibo
            val guardada = tree(send("GET", "/api/objects/orden_de_cobro/records/$ordenId", null, HttpStatus.OK))["attributes"]
            if (guardada["estado"].asString() == "ANULADA") {
                assertTrue(guardada["recibo"] == null || guardada["recibo"].isNull, guardada.toString())
                assertTrue(registros("linea_recibo", "orden" to ordenId).isEmpty())
            } else {
                assertEquals("PAGADA", guardada["estado"].asString())
            }
        }
    }

    @Test
    fun `el cobro de una ANULADA es 422`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val alta = alta(rentas)
        val ordenId = alta["orden_id"].asString()
        send("POST", RETIRO, retiro(alta), HttpStatus.OK, rentas)
        val caja = nuevaCaja()

        val problema = tree(send("POST", COBROS, cobro(caja, ordenId), HttpStatus.UNPROCESSABLE_CONTENT, funcionario("CAJERO")))

        assertTrue(problema["detail"].asString().contains("el sistema que la emitió la retiró"), problema.toString())
        assertTrue(problema["detail"].asString().contains(MOTIVO), problema.toString())
        assertTrue(registros("recibo", "caja" to caja.id).isEmpty())
    }

    @Test
    fun `la vista previa de una ANULADA sigue siendo 200 con cobrable false`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val alta = alta(rentas)
        val ordenId = alta["orden_id"].asString()
        send("POST", RETIRO, retiro(alta), HttpStatus.OK, rentas)

        val vista = tree(send("POST", VISTA_PREVIA, mapOf("ordenes" to listOf(ordenId)), HttpStatus.OK, funcionario("CAJERO")))

        assertFalse(vista["cobrable"].asBoolean(), vista.toString())
        assertTrue(vista["motivos"][0].asString().contains("la retiró"), vista.toString())
        assertTrue(vista["lineas"].isEmpty, vista.toString())
    }

    @Test
    fun `la lista de pendientes no la muestra, estado=ANULADA si, con su motivo`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val documento = "4${unico().take(7)}"
        val alta = alta(rentas, pagador = documento)
        val otra = alta(rentas, pagador = documento)
        send("POST", RETIRO, retiro(alta), HttpStatus.OK, rentas)

        val pendientes = tree(send("GET", "$ORDENES?pagador_documento=$documento", null, HttpStatus.OK))["content"].toList()
        assertEquals(listOf(otra["orden_id"].asString()), pendientes.map { it["orden_id"].asString() })
        assertTrue(pendientes.single()["motivo_retiro"].isNull, pendientes.toString())

        val anuladas = tree(send("GET", "$ORDENES?pagador_documento=$documento&estado=ANULADA", null, HttpStatus.OK))["content"].toList()
        assertEquals(listOf(alta["orden_id"].asString()), anuladas.map { it["orden_id"].asString() })
        assertEquals(MOTIVO, anuladas.single()["motivo_retiro"].asString())
        assertFalse(anuladas.single()["retirada_en"].isNull, anuladas.toString())
    }

    @Test
    fun `una orden devuelta a PENDIENTE por la anulacion del recibo se puede retirar`() {
        val (_, rentas) = cuentaDeServicio("rentas")
        val alta = alta(rentas)
        val ordenId = alta["orden_id"].asString()
        val numero = post(COBROS, cobro(nuevaCaja(), ordenId), funcionario("CAJERO"))["recibo"]["numero_impreso"].asString()
        send("POST", RETIRO, retiro(alta), HttpStatus.CONFLICT, rentas)
        post(
            "/api/caja/recibos/$numero/anulacion",
            mapOf("motivo" to "COBRO EN DEMASÍA", "observacion" to "el pagador pagó dos veces en ventanilla"),
            funcionario("SUPERVISOR_CAJA")
        )
        assertEquals("PENDIENTE", estadoDe(ordenId))

        val retirada = tree(send("POST", RETIRO, retiro(alta), HttpStatus.OK, rentas))

        assertEquals("ANULADA", retirada["estado"].asString())
    }

    // una orden dada de alta por la cuenta de servicio: su sistema es el nombre de la cuenta
    private fun alta(
        cuenta: String,
        referencia: String? = null,
        pagador: String? = null
    ): JsonNode {
        var cuerpo = orden() - "sistema_origen"
        if (referencia != null) cuerpo = cuerpo + ("referencia_externa" to referencia)
        if (pagador != null) cuerpo = cuerpo + ("pagador_documento" to pagador)
        return post(ORDENES, cuerpo, cuenta)
    }

    private fun retiro(
        alta: JsonNode,
        motivo: String = MOTIVO
    ): Map<String, Any?> = mapOf("referencia_externa" to alta["referencia_externa"].asString(), "motivo" to motivo)

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

    private fun razonDelUltimo(
        id: String,
        operacion: String
    ): String =
        tree(send("GET", "/api/objects/orden_de_cobro/records/$id/history", null, HttpStatus.OK))
            .toList()
            .first { it["operation"].asString() == operacion }["reason"]
            .asString()

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val RETIRO = "/api/caja/ordenes-de-cobro/retiro"
        const val COBROS = "/api/caja/cobros"
        const val VISTA_PREVIA = "/api/caja/cobros/vista-previa"
        const val MOTIVO = "Redeterminación por la DJ n° 12"
        const val RAZON = "retiro de la orden por su sistema de origen"
    }
}
