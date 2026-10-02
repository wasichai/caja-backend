package caja.turno

import caja.CajaApiTest
import caja.comun.LIMA
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// el cierre es todo o nada (hallazgo de la revisión del PR 7): un fallo a mitad, cuando el acta ya está escrita y falta
// una de sus líneas, no deja ningún cierre_turno, y el turno sigue ABIERTO. un RecordChangeListener corre dentro de la
// transacción, justo después de cada escritura: este revienta al crearse una cierre_turno_linea
class CierreEnUnaTransaccionApiTest : CajaApiTest() {
    @TestConfiguration
    class FalloEnLaLinea {
        @Bean
        fun revientaEnLaLinea() =
            object : RecordChangeListener {
                override suspend fun recordChanged(change: RecordChange) {
                    if (!armado.get()) return
                    vistos += "${change.objectName} ${change.kind}"
                    if (change.objectName == "cierre_turno") acta.set(change.recordId.toString())
                    if (change.objectName == "cierre_turno_linea" && change.kind == RecordChangeKind.CREATED) {
                        throw IllegalStateException("fallo simulado al escribir una línea del cierre")
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
    fun `un fallo al escribir una linea no deja cierre y el turno sigue abierto`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val tasa = codigoDeTasa()
        nuevaTasa(tasa, "12.30", LocalDate.now(LIMA).minusDays(1))
        post(
            "/api/caja/cobros/tasas",
            mapOf(
                "caja" to caja.codigo,
                "forma_pago" to "EFECTIVO",
                "conceptos" to listOf(mapOf("codigo" to tasa, "cantidad" to 1)),
                "observacion" to "cobro de tasas en ventanilla"
            ),
            cajero.token
        )
        val turno = registros("turno", "caja" to caja.id).single()["id"].asString()
        val cierre = mapOf("caja" to caja.codigo, "declarado" to mapOf("EFECTIVO" to "12.30"), "observacion" to "cierre del turno")

        armado.set(true)
        send("POST", CIERRE, cierre, HttpStatus.INTERNAL_SERVER_ERROR, cajero.token)
        armado.set(false)

        // el acta ya estaba escrita cuando reventó la línea
        assertEquals(listOf("cierre_turno CREATED", "cierre_turno_linea CREATED"), vistos.toList())
        // y la transacción se la llevó
        assertEquals(0, registros("cierre_turno", "turno" to turno).size, "ningún cierre")
        assertEquals(0, registros("cierre_turno_linea", "cierre_turno" to acta.get()).size, "ninguna línea")
        val arqueo = tree(send("GET", "/api/caja/turnos/$turno/arqueo", null, HttpStatus.OK, cajero.token))
        assertEquals("ABIERTO", arqueo["estado_del_turno"].asString())

        // desarmado, el mismo cierre se escribe con la secuencia 1: tampoco quedó un hueco
        val hecho = post(CIERRE, cierre, cajero.token)
        assertEquals(1L, hecho["secuencia"].asLong())
        assertEquals(1, registros("cierre_turno", "turno" to turno).size)
    }

    private companion object {
        const val CIERRE = "/api/caja/turnos/cierre"
        val armado = AtomicBoolean(false)
        val vistos: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val acta = AtomicReference<String>()
    }
}
