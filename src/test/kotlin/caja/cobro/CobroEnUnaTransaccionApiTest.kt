package caja.cobro

import caja.CajaApiTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

// la prueba de la transacción: un fallo a mitad no deja nada. RecordService no abre transacción (ADR-0025 de
// wasichai), pero escribe por DatabaseClient, que se une a la que abre la cobranza. un RecordChangeListener corre justo
// después de cada escritura, dentro de ella: este revienta al crearse el pago_evento, cuando el turno, el recibo, su
// línea y la orden PAGADA ya están escritos. si wasichai no se uniera a la transacción, quedarían
class CobroEnUnaTransaccionApiTest : CajaApiTest() {
    @TestConfiguration
    class FalloAlEncolar {
        @Bean
        fun revientaAlEncolar() =
            object : RecordChangeListener {
                override suspend fun recordChanged(change: RecordChange) {
                    if (!armado.get()) return
                    vistos += "${change.objectName} ${change.kind}"
                    if (change.objectName == "pago_evento" && change.kind == RecordChangeKind.CREATED) {
                        throw IllegalStateException("fallo simulado al encolar el evento")
                    }
                }
            }
    }

    @AfterEach
    fun desarmar() {
        armado.set(false)
        vistos.clear()
    }

    @Test
    fun `un fallo al encolar el evento no deja turno, recibo, linea ni orden pagada`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        val cuerpo = mapOf("caja" to caja.codigo, "forma_pago" to "EFECTIVO", "ordenes" to listOf(ordenId), "observacion" to "cobro en ventanilla")

        armado.set(true)
        send("POST", COBROS, cuerpo, HttpStatus.INTERNAL_SERVER_ERROR, cajero.token)
        armado.set(false)

        // el listener vio cada escritura antes de reventar: estaban hechas
        assertEquals(
            listOf("turno CREATED", "recibo CREATED", "linea_recibo CREATED", "orden_de_cobro UPDATED", "pago_evento CREATED"),
            vistos.toList()
        )
        // y la transacción se las llevó todas
        assertEquals(0, registros("turno", "caja" to caja.id).size, "ningún turno nuevo")
        assertEquals(0, registros("recibo", "caja" to caja.id).size, "ningún recibo")
        assertEquals(0, registros("linea_recibo", "orden" to ordenId).size, "ninguna línea")
        val orden = tree(send("GET", "/api/objects/orden_de_cobro/records/$ordenId", null, HttpStatus.OK))["attributes"]
        assertEquals("PENDIENTE", orden["estado"].asString(), "la orden sigue cobrable: nadie pagó")
        assertTrue(orden["recibo"] == null || orden["recibo"].isNull, orden.toString())

        // el número tampoco avanzó: el reintento emite el 1 de la serie
        val reintento = post(COBROS, cuerpo, cajero.token)
        assertEquals(1L, reintento["recibo"]["numero"].asLong())
        assertEquals(1, registros("turno", "caja" to caja.id).size)
    }

    @Test
    fun `dentro de la transaccion core aplica los permisos del cajero, y su 403 tampoco deja nada`() {
        // puede todo menos encolar el evento: core lo rechaza al final, como el usuario que llama y no como nadie
        val sinBuzon =
            funcionario(
                listOf(
                    permiso("caja", "READ"),
                    permiso("orden_de_cobro", "READ"),
                    permiso("orden_de_cobro", "UPDATE"),
                    permiso("turno", "READ"),
                    permiso("turno", "CREATE"),
                    permiso("recibo", "READ"),
                    permiso("recibo", "CREATE"),
                    permiso("linea_recibo", "READ"),
                    permiso("linea_recibo", "CREATE"),
                    permiso("pago_evento", "READ"),
                    // el cobro lee la historia del turno: un turno cerrado no cobra
                    permiso("cierre_turno", "READ"),
                    permiso("reversion_cierre", "READ")
                )
            )
        val caja = nuevaCaja()
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        val cuerpo = mapOf("caja" to caja.codigo, "forma_pago" to "EFECTIVO", "ordenes" to listOf(ordenId), "observacion" to "cobro en ventanilla")

        val problema = tree(send("POST", COBROS, cuerpo, HttpStatus.FORBIDDEN, sinBuzon))
        // el de core al crear el pago_evento, no el de la entrada del cobro
        assertEquals("Missing permission CREATE", problema["detail"].asString(), problema.toString())

        assertEquals(0, registros("turno", "caja" to caja.id).size)
        assertEquals(0, registros("recibo", "caja" to caja.id).size)
        assertEquals(0, registros("linea_recibo", "orden" to ordenId).size)
        val orden = tree(send("GET", "/api/objects/orden_de_cobro/records/$ordenId", null, HttpStatus.OK))["attributes"]
        assertEquals("PENDIENTE", orden["estado"].asString())
    }

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val COBROS = "/api/caja/cobros"

        // solo esta prueba lo arma: el resto del contexto cobra como siempre
        val armado = AtomicBoolean(false)
        val vistos: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }
}
