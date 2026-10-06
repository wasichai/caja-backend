package caja.comun

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import java.io.File
import java.util.UUID

// la guarda antes de escribir (caja-backend#20), sin Spring ni base: lo que deja pasar y lo que no, con la marca
// EscrituraDeCaja y sin ella, sobre el RecordWrite que wasichai le da
@ExtendWith(OutputCaptureExtension::class)
class GuardiaDeEscriturasTest {
    private val guardia = GuardiaDeEscrituras()

    @Test
    fun `sin la marca, ningun objeto de caja se escribe`(salida: CapturedOutput) {
        val escritas = listOf(RecordChangeKind.CREATED, RecordChangeKind.UPDATED, RecordChangeKind.TRANSITIONED)
        GuardiaDeEscrituras.PROTEGIDOS.forEach { objeto ->
            escritas.forEach { clase ->
                val rechazo = rechaza { guardia.beforeWrite(escritura(objeto, clase)) }
                assertTrue(rechazo.message.contains("«$objeto»") && rechazo.message.contains("caja-backend#20"), rechazo.message)
            }
        }
        assertEquals(GuardiaDeEscrituras.PROTEGIDOS.size * escritas.size, rechazos(salida).size, "una línea WARN por rechazo")
    }

    @Test
    fun `la linea WARN nombra el objeto, el id y quien escribe, o la plataforma`(salida: CapturedOutput) {
        val id = UUID.randomUUID()
        rechaza { guardia.beforeWrite(escritura(RECIBO, RecordChangeKind.UPDATED, recordId = id)) }
        rechaza { guardia.beforeWrite(escritura(RECIBO, RecordChangeKind.CREATED, userId = null)) }

        val (delUsuario, delaPlataforma) = rechazos(salida)
        assertTrue(delUsuario.contains("UPDATE recibo $id por el usuario $USUARIO de la organización $ORGANIZACION"), delUsuario)
        assertTrue(delaPlataforma.contains("CREATE recibo (alta) por la plataforma de la organización $ORGANIZACION"), delaPlataforma)
    }

    @Test
    fun `el alta de una orden con el importe roto lo dice en la linea`(salida: CapturedOutput) {
        rechaza { guardia.beforeWrite(escritura(ORDEN_DE_COBRO, RecordChangeKind.CREATED, atributos = mapOf("importe" to "-50.00"))) }
        rechaza { guardia.beforeWrite(escritura(ORDEN_DE_COBRO, RecordChangeKind.CREATED, atributos = mapOf("importe" to "80.00"))) }

        val (roto, sano) = rechazos(salida)
        assertTrue(roto.contains("importe roto (-50.00)") && roto.contains("debe ser mayor que 0"), roto)
        assertTrue(!sano.contains("importe roto"), sano)
    }

    @Test
    fun `con la marca, caja escribe todo lo suyo`() {
        runBlocking {
            withContext(EscrituraDeCaja) {
                GuardiaDeEscrituras.PROTEGIDOS.forEach { objeto ->
                    listOf(RecordChangeKind.CREATED, RecordChangeKind.UPDATED, RecordChangeKind.TRANSITIONED).forEach {
                        guardia.beforeWrite(escritura(objeto, it))
                    }
                }
            }
        }
    }

    @Test
    fun `nada de caja se borra nunca, ni con la marca`(salida: CapturedOutput) {
        GuardiaDeEscrituras.PROTEGIDOS.forEach { objeto ->
            val sinMarca = rechaza { guardia.beforeWrite(escritura(objeto, RecordChangeKind.DELETED)) }
            assertTrue(sinMarca.message.contains("no se borra"), sinMarca.message)
            rechaza { withContext(EscrituraDeCaja) { guardia.beforeWrite(escritura(objeto, RecordChangeKind.DELETED)) } }
        }
        assertEquals(GuardiaDeEscrituras.PROTEGIDOS.size * 2, rechazos(salida).size)
    }

    @Test
    fun `lo que no es de caja pasa sin la marca, se escriba como se escriba`(salida: CapturedOutput) {
        listOf(AREA, CAJA, TASA).forEach { objeto ->
            RecordChangeKind.entries.forEach { runBlocking { guardia.beforeWrite(escritura(objeto, it)) } }
        }
        assertEquals(emptyList<String>(), rechazos(salida))
    }

    @Test
    fun `los objetos de caja son los diez del modelo que no son catalogo`() {
        assertEquals(
            setOf(
                RECIBO,
                LINEA_RECIBO,
                ANULACION_RECIBO,
                REIMPRESION_RECIBO,
                CIERRE_TURNO,
                CIERRE_TURNO_LINEA,
                REVERSION_CIERRE,
                PAGO_EVENTO,
                TURNO,
                ORDEN_DE_COBRO
            ),
            GuardiaDeEscrituras.PROTEGIDOS
        )
    }

    // el modelo y la guarda nombran los mismos objetos: los apiOnly de model/model.json (la API genérica da 403 desde
    // wasichai) son exactamente los que la guarda protege dentro del proceso. un objeto de caja que entre en uno y no en
    // el otro queda con una sola de las dos puertas cerrada
    @Test
    fun `los objetos apiOnly del modelo son exactamente los protegidos`() {
        val apiOnly =
            JsonMapper
                .builder()
                .build()
                .readTree(File("model/model.json"))["objects"]
                .filter { it["apiOnly"]?.booleanValue() == true }
                .map { it["name"].asString() }
                .toSet()
        assertEquals(GuardiaDeEscrituras.PROTEGIDOS, apiOnly)
    }

    private fun rechaza(bloque: suspend () -> Unit): ForbiddenException = assertThrows(ForbiddenException::class.java) { runBlocking { bloque() } }

    private fun escritura(
        objeto: String,
        clase: RecordChangeKind,
        userId: UUID? = USUARIO,
        recordId: UUID? = if (clase == RecordChangeKind.CREATED) null else UUID.randomUUID(),
        atributos: Map<String, Any?>? = null
    ) = RecordWrite(ORGANIZACION, userId, UUID.randomUUID(), objeto, recordId, clase, attributes = atributos)

    private fun rechazos(salida: CapturedOutput): List<String> =
        salida.out.lines().filter {
            it.contains(" WARN ") &&
                it.contains("ESCRITURA FUERA DE CAJA RECHAZADA: ")
        }

    private companion object {
        val ORGANIZACION: UUID = UUID.randomUUID()
        val USUARIO: UUID = UUID.randomUUID()
    }
}
