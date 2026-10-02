package caja.buzon

import caja.CajaApiTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource

// con el buzón encendido, el bucle entrega solo, sin que nadie dé la vuelta: el pago cobrado llega al sistema de
// origen. intentos alto: los eventos de otras pruebas, sin destino, solo suman intentos y no mueren. el contexto se
// cierra con la clase, y su bucle con él
@DirtiesContext
@TestPropertySource(
    properties = [
        "caja.buzon.habilitado=true",
        "caja.buzon.intervalo=PT0.2S",
        "caja.buzon.intentos=100000",
        "caja.buzon.por-vuelta=100000",
        "caja.conciliacion.responsable=Ana Quispe",
        "caja.conciliacion.canal=conciliacion@muni.gob.pe"
    ]
)
class BucleDelBuzonApiTest : CajaApiTest() {
    @Test
    fun `el bucle entrega el pago cobrado sin que nadie de la vuelta`() {
        val caja = nuevaCaja()
        val alta = post("/api/caja/ordenes-de-cobro", orden("sistema_origen" to SISTEMA))
        val cobro =
            post(
                "/api/caja/cobros",
                mapOf(
                    "caja" to caja.codigo,
                    "forma_pago" to "EFECTIVO",
                    "ordenes" to listOf(alta["orden_id"].asString()),
                    "observacion" to "cobro en ventanilla"
                ),
                funcionario("CAJERO")
            )
        val pagoId = cobro["pago_id"].asString()
        origen.contestar(pagoId, 202)

        val hasta = System.nanoTime() + 30_000_000_000L
        while (estado(pagoId) != "ENTREGADO" && System.nanoTime() < hasta) Thread.sleep(100)

        assertEquals("ENTREGADO", estado(pagoId))
        // la primera vuelta pudo llegar antes de que el destino supiera qué contestar (503): una o dos llamadas
        assertTrue(origen.de(pagoId).isNotEmpty())
    }

    private fun estado(pagoId: String): String = registros("pago_evento", "evento_id" to pagoId).single()["attributes"]["estado"].asString()

    companion object {
        const val SISTEMA = "buzon-bucle"
        val origen = SistemaDeOrigenFalso()

        @JvmStatic
        @DynamicPropertySource
        fun destinos(registro: DynamicPropertyRegistry) {
            registro.add("caja.buzon.destinos.$SISTEMA.url") { origen.url() }
        }

        @JvmStatic
        @AfterAll
        fun apagar() = origen.close()
    }
}
