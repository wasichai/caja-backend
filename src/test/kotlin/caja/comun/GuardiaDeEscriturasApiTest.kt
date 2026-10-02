package caja.comun

import caja.CajaApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.OffsetDateTime

// el detector de la segunda puerta (wasichai#15): toda creación, cambio o borrado que se hace FUERA de la api de caja
// sobre un objeto protegido deja una línea ERROR. no veta nada, porque corre después de escribir: lo forjado queda
// escrito, y la línea es lo que permite verlo. lo que caja escribe por su api no la deja
@ExtendWith(OutputCaptureExtension::class)
class GuardiaDeEscriturasApiTest : CajaApiTest() {
    @Test
    fun `un cierre, sus lineas y su reversion forjados por la API generica se detectan`(salida: CapturedOutput) {
        val cajero = cuenta("CAJERO")
        val supervisor = cuenta("SUPERVISOR_CAJA")
        val turno = cobrarTasa(cuenta("CAJERO"))
        val cierre =
            crear(
                "cierre_turno",
                cajero.token,
                "turno" to turno,
                "secuencia" to 1,
                "fecha" to LocalDate.now(LIMA).toString(),
                "registrado_en" to OffsetDateTime.now(LIMA).toString(),
                "total_cobrado" to "0.00",
                "total_anulado" to "0.00",
                "neto" to "0.00",
                "total_declarado" to "0.00",
                "diferencia" to "0.00",
                "recibos_emitidos" to 0,
                "recibos_anulados" to 0,
                "cobrado_con_evento" to "0.00",
                "cobrado_sin_evento" to "0.00",
                "usuario" to cajero.email,
                "observacion" to "un cierre forjado",
                "clave_secuencia" to "$turno|1"
            )
        val linea =
            crear(
                "cierre_turno_linea",
                cajero.token,
                "cierre_turno" to cierre,
                "forma_pago" to "EFECTIVO",
                "cobrado" to "0.00",
                "anulado" to "0.00",
                "neto" to "0.00",
                "declarado" to "0.00",
                "clave" to "$cierre|EFECTIVO"
            )
        val reversion =
            crear(
                "reversion_cierre",
                supervisor.token,
                "turno" to turno,
                "cierre_revertido" to cierre,
                "secuencia" to 2,
                "motivo" to "FORJADA",
                "fecha" to LocalDate.now(LIMA).toString(),
                "registrado_en" to OffsetDateTime.now(LIMA).toString(),
                "usuario" to supervisor.email,
                "observacion" to "una reversión forjada",
                "clave_secuencia" to "$turno|2"
            )

        listOf("cierre_turno" to cierre, "cierre_turno_linea" to linea, "reversion_cierre" to reversion).forEach { (objeto, id) ->
            val detectada = lineaDe(salida, id)
            assertTrue(detectada.contains(" ERROR "), detectada)
            assertTrue(detectada.contains("CREATED") && detectada.contains(objeto), detectada)
        }
    }

    @Test
    fun `lo que caja escribe por su api no se detecta, y un cambio de una orden por fuera si`(salida: CapturedOutput) {
        val cajero = cuenta("CAJERO")
        val caja = nuevaCaja()
        val orden = post("/api/caja/ordenes-de-cobro", orden())["orden_id"].asString()
        val cobro =
            post(
                "/api/caja/cobros",
                mapOf("caja" to caja.codigo, "forma_pago" to "EFECTIVO", "ordenes" to listOf(orden), "observacion" to "cobro en ventanilla"),
                cajero.token
            )
        val recibo = registros("recibo", "numero_impreso" to cobro["recibo"]["numero_impreso"].asString()).single()["id"].asString()
        val evento = registros("pago_evento", "evento_id" to cobro["pago_id"].asString()).single()["id"].asString()
        val turno = registros("turno", "caja" to caja.id).single()["id"].asString()
        val linea = registros("linea_recibo", "recibo" to recibo).single()["id"].asString()
        listOf(recibo, evento, turno, linea, orden).forEach { id ->
            assertEquals(emptyList<String>(), detectadas(salida, id), "la cobranza escribió $id por la api de caja")
        }
        // la caja la escribió el admin por la api genérica, pero no es un objeto protegido
        assertEquals(emptyList<String>(), detectadas(salida, caja.id))

        // la orden, cambiada por fuera de caja: la marcaría PAGADA sin recibo, o la devolvería a PENDIENTE
        cambiarComoAdmin("orden_de_cobro", orden, "observacion" to "cambiada por fuera de caja")
        assertTrue(lineaDe(salida, orden).contains("UPDATED"))
    }

    private fun cobrarTasa(cajero: Cuenta): String {
        val tasa = codigoDeTasa()
        nuevaTasa(tasa, "12.30", LocalDate.now(LIMA).minusDays(1))
        val caja = nuevaCaja()
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
        return registros("turno", "caja" to caja.id).single()["id"].asString()
    }

    private fun crear(
        objeto: String,
        token: String,
        vararg atributos: Pair<String, Any?>
    ): String = tree(send("POST", "/api/objects/$objeto/records", mapOf("attributes" to atributos.toMap()), HttpStatus.CREATED, token))["id"].asString()

    private fun detectadas(
        salida: CapturedOutput,
        id: String
    ): List<String> = salida.out.lines().filter { it.contains("ESCRITURA FUERA DE CAJA") && it.contains(id) }

    private fun lineaDe(
        salida: CapturedOutput,
        id: String
    ): String = detectadas(salida, id).singleOrNull() ?: error("el detector no vio $id: ${detectadas(salida, id)}")
}
