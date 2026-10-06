package caja.comun

import caja.CajaApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// la segunda puerta (caja-backend#20): ningún objeto de caja se escribe por la API genérica de wasichai
// (POST/PUT/DELETE /api/objects/{objeto}/records), ni con el permiso del rol ni como ADMIN. los diez son apiOnly: 403
// desde wasichai, antes de tocar la base y sin línea WARN de caja. lo que corre dentro del proceso sin la marca de caja
// lo rechaza GuardiaDeEscrituras, con su WARN. lo que caja escribe por su api pasa (lo prueba también el resto de la
// suite), y lo que no es de caja (una caja, un área) se sigue escribiendo por esa puerta
@ExtendWith(OutputCaptureExtension::class)
class GuardiaDeEscriturasApiTest : CajaApiTest() {
    @Autowired
    private lateinit var records: RecordService

    // los dos vectores del #20

    @Test
    fun `un acta de anulacion forjada no se escribe, y el arqueo del turno no cambia`() {
        val cajero = cuenta("CAJERO")
        val supervisor = cuenta("SUPERVISOR_CAJA")
        val cobro = cobrar(cajero)
        val antes = arqueo(cobro.turno, cajero)

        // un SUPERVISOR_CAJA tiene CREATE sobre anulacion_recibo para anular: por la API genérica se saltaría el mismo
        // día, el turno abierto, las órdenes que vuelven a PENDIENTE y el PAGO_ANULADO
        val acta =
            mapOf(
                "recibo" to cobro.recibo,
                "recibo_anulado" to cobro.recibo,
                "caja" to cobro.caja.id,
                "turno" to cobro.turno,
                "fecha" to LocalDate.now(LIMA).toString(),
                "motivo" to "UN ACTA FORJADA",
                "importe" to "150.50",
                "usuario" to supervisor.email,
                "observacion" to "el dinero sale del cajón y el cierre cuadra igual"
            )
        val problema = tree(send("POST", "/api/objects/anulacion_recibo/records", mapOf("attributes" to acta), HttpStatus.FORBIDDEN, supervisor.token))

        assertTrue(problema["detail"].asString().contains("anulacion_recibo"), problema.toString())
        assertTrue(registros("anulacion_recibo", "recibo" to cobro.recibo).isEmpty(), "no quedó ningún acta")
        assertEquals(antes, arqueo(cobro.turno, cajero), "el arqueo no resta nada")

        // la anulación de verdad, por la api de caja, sí
        post("/api/caja/recibos/${cobro.numero}/anulacion", ANULACION, supervisor.token)
        assertEquals(1, registros("anulacion_recibo", "recibo" to cobro.recibo).size)
    }

    @Test
    fun `un pago ENTREGADO no se reenvia con otro pagoId, ni uno PENDIENTE se explica por fuera`() {
        val supervisor = cuenta("SUPERVISOR_CAJA")
        val entregado = cobrar(cuenta("CAJERO"))
        val evento = registros("pago_evento", "evento_id" to entregado.pagoId).single()
        cambiarEnLaBase("pago_evento", evento["id"].asString(), "estado" to "ENTREGADO", "entregado_en" to OffsetDateTime.now(LIMA).toString())
        val guardado = registros("pago_evento", "evento_id" to entregado.pagoId).single()

        // el UPDATE del supervisor (para explicar un pago) cambiaría el evento_id y el pagoId del cuerpo y lo volvería a
        // PENDIENTE: la fila conserva su sello, pasa la defensa (a) y se reenvía con otro pagoId
        val otroPagoId = UUID.randomUUID().toString()
        val reenviado =
            atributos(guardado) +
                mapOf(
                    "evento_id" to otroPagoId,
                    "cuerpo" to guardado["attributes"]["cuerpo"].asString().replace(entregado.pagoId, otroPagoId),
                    "estado" to "PENDIENTE"
                )
        send("PUT", "/api/objects/pago_evento/records/${evento["id"].asString()}", mapOf("attributes" to reenviado), HttpStatus.FORBIDDEN, supervisor.token)
        val tras = registros("pago_evento", "evento_id" to entregado.pagoId).single()
        assertEquals("ENTREGADO", tras["attributes"]["estado"].asString())
        assertEquals(guardado["updatedAt"], tras["updatedAt"], "la fila no se tocó")
        assertTrue(registros("pago_evento", "evento_id" to otroPagoId).isEmpty())

        // ni un PENDIENTE pasa a EXPLICADO sin explicación, para que su turno cierre: eso es POST /api/caja/pagos/.../explicacion
        val pendiente = cobrar(cuenta("CAJERO"))
        val suyo = registros("pago_evento", "evento_id" to pendiente.pagoId).single()
        val explicado = atributos(suyo) + ("estado" to "EXPLICADO")
        send("PUT", "/api/objects/pago_evento/records/${suyo["id"].asString()}", mapOf("attributes" to explicado), HttpStatus.FORBIDDEN, supervisor.token)
        assertEquals("PENDIENTE", registros("pago_evento", "evento_id" to pendiente.pagoId).single()["attributes"]["estado"].asString())
    }

    // todos los objetos de caja

    @Test
    fun `ningun objeto de caja se da de alta, se cambia ni se borra por la API generica, ni como ADMIN`() {
        val supervisor = cuenta("SUPERVISOR_CAJA")
        // un turno con todo lo que escribe caja: el cobro, un duplicado, la anulación, el cierre y su reversión
        val cobro = cobrar(supervisor)
        // el duplicado contesta el pdf: solo se mira el 201
        send(
            "POST",
            "/api/caja/recibos/${cobro.numero}/duplicados",
            mapOf("observacion" to "reimpresión pedida por el pagador"),
            HttpStatus.CREATED,
            supervisor.token
        )
        post("/api/caja/recibos/${cobro.numero}/anulacion", ANULACION, supervisor.token)
        registros("pago_evento", "turno" to cobro.turno).forEach {
            cambiarEnLaBase("pago_evento", it["id"].asString(), "estado" to "ENTREGADO", "entregado_en" to OffsetDateTime.now(LIMA).toString())
        }
        val cierre =
            post(
                "/api/caja/turnos/cierre",
                mapOf("caja" to cobro.caja.codigo, "declarado" to mapOf("EFECTIVO" to "0.00"), "observacion" to "cierre del turno"),
                supervisor.token
            )
        post(
            "/api/caja/turnos/reversion",
            mapOf("caja" to cobro.caja.codigo, "motivo" to "ARQUEO MAL CONTADO", "observacion" to "se contó mal el cajón"),
            supervisor.token
        )
        val cierreId = cierre["cierre_id"].asString()
        val uno =
            mapOf(
                "recibo" to registros("recibo", "numero_impreso" to cobro.numero).single(),
                "linea_recibo" to registros("linea_recibo", "recibo" to cobro.recibo).single(),
                "pago_evento" to registros("pago_evento", "evento_id" to cobro.pagoId).single(),
                "anulacion_recibo" to registros("anulacion_recibo", "recibo" to cobro.recibo).single(),
                "reimpresion_recibo" to registros("reimpresion_recibo", "recibo" to cobro.recibo).single(),
                "turno" to registros("turno", "caja" to cobro.caja.id).single(),
                "cierre_turno" to registros("cierre_turno", "turno" to cobro.turno).single(),
                "cierre_turno_linea" to registros("cierre_turno_linea", "cierre_turno" to cierreId).first(),
                "reversion_cierre" to registros("reversion_cierre", "turno" to cobro.turno).single(),
                "orden_de_cobro" to registros("orden_de_cobro", "referencia_externa" to cobro.referencia).single()
            )
        assertEquals(GuardiaDeEscrituras.PROTEGIDOS, uno.keys, "un registro de cada objeto de caja")

        uno.forEach { (objeto, registro) ->
            val id = registro["id"].asString()
            val ruta = "/api/objects/$objeto/records"
            val cuantos = total(objeto)

            val alta = tree(send("POST", ruta, mapOf("attributes" to atributos(registro)), HttpStatus.FORBIDDEN))
            assertTrue(alta["detail"].asString().contains("'$objeto'"), alta.toString())
            send("PUT", "$ruta/$id", mapOf("attributes" to atributos(registro)), HttpStatus.FORBIDDEN)
            send("DELETE", "$ruta/$id", null, HttpStatus.FORBIDDEN)

            val tras = tree(send("GET", "$ruta/$id", null, HttpStatus.OK))
            assertEquals(registro["attributes"], tras["attributes"], "$objeto no cambió")
            assertEquals(registro["updatedAt"], tras["updatedAt"], "$objeto no se tocó")
            assertEquals(cuantos, total(objeto), "ningún $objeto de más")
        }
    }

    // la orden de cobro: su única puerta es el alta de caja

    @Test
    fun `una orden no se da de alta ni se cambia por la API generica, y por el alta de caja si`() {
        val referencia = "FUERA-${unico()}"
        val porFuera =
            mapOf(
                "sistema_origen" to "rentas",
                "referencia_externa" to referencia,
                "clave_origen" to "rentas|$referencia",
                "concepto" to "IMPUESTO PREDIAL 2026 - CUOTA 1",
                "importe" to "-50.00",
                "fecha_exigibilidad" to LocalDate.now(LIMA).toString(),
                "actualizado_a" to LocalDate.now(LIMA).toString(),
                "estado" to "PENDIENTE",
                "observacion" to "escrita por la API genérica"
            )
        send("POST", "/api/objects/orden_de_cobro/records", mapOf("attributes" to porFuera), HttpStatus.FORBIDDEN, funcionario("SISTEMA_ORIGEN"))
        assertTrue(registros("orden_de_cobro", "referencia_externa" to referencia).isEmpty())

        // el alta de caja, sí
        val orden = post("/api/caja/ordenes-de-cobro", orden("importe" to "80.00"))["orden_id"].asString()
        val guardada = tree(send("GET", "/api/objects/orden_de_cobro/records/$orden", null, HttpStatus.OK))

        // un CAJERO tiene UPDATE sobre orden_de_cobro para cobrarla: no le baja el importe antes de cobrarla
        val rebajada = atributos(guardada) + ("importe" to "0.50")
        send("PUT", "/api/objects/orden_de_cobro/records/$orden", mapOf("attributes" to rebajada), HttpStatus.FORBIDDEN, funcionario("CAJERO"))
        val tras = tree(send("GET", "/api/objects/orden_de_cobro/records/$orden", null, HttpStatus.OK))
        assertEquals(guardada["attributes"], tras["attributes"])
    }

    // lo que corre dentro del proceso sin la marca de caja: wasichai ya no lo ve por REST, y la guarda lo rechaza con su WARN
    @Test
    fun `una escritura en proceso sin la marca de caja se rechaza, con su linea WARN`(salida: CapturedOutput) {
        val referencia = "EN-PROCESO-${unico()}"
        val porFuera =
            mapOf(
                "sistema_origen" to "rentas",
                "referencia_externa" to referencia,
                "clave_origen" to "rentas|$referencia",
                "concepto" to "IMPUESTO PREDIAL 2026 - CUOTA 1",
                "importe" to "-50.00",
                "fecha_exigibilidad" to LocalDate.now(LIMA).toString(),
                "actualizado_a" to LocalDate.now(LIMA).toString(),
                "estado" to "PENDIENTE",
                "observacion" to "escrita por un módulo"
            )
        assertThrows(ForbiddenException::class.java) {
            runBlocking { records.asPlatform(organizacion()) { records.create(ORDEN_DE_COBRO, RecordRequest(porFuera), "escrita por un módulo") } }
        }

        assertTrue(registros("orden_de_cobro", "referencia_externa" to referencia).isEmpty(), "no quedó ninguna fila")
        // la línea nombra el importe que el alta habría rechazado: el que rompería un recibo, y que lo escribió la plataforma
        val linea = rechazos(salida, "CREATE orden_de_cobro").single { it.contains("-50.00") }
        assertTrue(linea.contains("la plataforma") && linea.contains("importe roto") && linea.contains("debe ser mayor que 0"), linea)
    }

    @Test
    fun `lo que no es de caja se sigue escribiendo por la API generica`(salida: CapturedOutput) {
        val caja = nuevaCaja()
        cambiarComoAdmin("caja", caja.id, "activa" to false)
        assertEquals(false, tree(send("GET", "/api/objects/caja/records/${caja.id}", null, HttpStatus.OK))["attributes"]["activa"].asBoolean())
        assertTrue(rechazos(salida, "caja").none { it.contains(caja.id) })
    }

    // ayudas

    private class Cobro(
        val numero: String,
        val recibo: String,
        val turno: String,
        val pagoId: String,
        val referencia: String,
        val caja: CajaDePrueba
    )

    // una orden de rentas, cobrada en una caja nueva por la api de caja
    private fun cobrar(cajero: Cuenta): Cobro {
        val caja = nuevaCaja()
        val alta = post("/api/caja/ordenes-de-cobro", orden())
        val cobro =
            post(
                "/api/caja/cobros",
                mapOf(
                    "caja" to caja.codigo,
                    "forma_pago" to "EFECTIVO",
                    "ordenes" to listOf(alta["orden_id"].asString()),
                    "observacion" to "cobro en ventanilla"
                ),
                cajero.token
            )
        val numero = cobro["recibo"]["numero_impreso"].asString()
        val recibo = registros("recibo", "numero_impreso" to numero).single()["id"].asString()
        val turno = registros("turno", "caja" to caja.id).single()["id"].asString()
        return Cobro(numero, recibo, turno, cobro["pago_id"].asString(), alta["referencia_externa"].asString(), caja)
    }

    private fun arqueo(
        turno: String,
        cajero: Cuenta
    ): JsonNode = tree(send("GET", "/api/caja/turnos/$turno/arqueo", null, HttpStatus.OK, cajero.token))["arqueo"]

    // cuántos registros de ese objeto hay en la base compartida
    private fun total(objeto: String): Long = tree(send("GET", "/api/objects/$objeto/records?size=1", null, HttpStatus.OK))["totalElements"].asLong()

    @Suppress("UNCHECKED_CAST")
    private fun atributos(registro: JsonNode): Map<String, Any?> = json.convertValue(registro["attributes"], Map::class.java) as Map<String, Any?>

    // las líneas WARN de la guarda que nombran eso
    private fun rechazos(
        salida: CapturedOutput,
        que: String
    ): List<String> = salida.out.lines().filter { it.contains(" WARN ") && it.contains("ESCRITURA FUERA DE CAJA RECHAZADA: ") && it.contains(que) }

    private companion object {
        val ANULACION = mapOf("motivo" to "COBRO EN DEMASÍA", "observacion" to "el pagador pagó dos veces en ventanilla")
    }
}
