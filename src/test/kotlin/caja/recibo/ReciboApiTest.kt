package caja.recibo

import caja.CajaApiTest
import caja.comun.LIMA
import caja.emision.texto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.OffsetDateTime

// la consulta de recibos (GET /api/caja/recibos y su ficha) y el duplicado en pdf (POST .../duplicados)
class ReciboApiTest : CajaApiTest() {
    private val hoy: LocalDate get() = LocalDate.now(LIMA)

    // el listado

    @Test
    fun `una busqueda sin resultados es una pagina vacia con total 0`() {
        val pagina = listado("documento=${documento()}")

        assertTrue(pagina["content"].isEmpty, pagina.toString())
        assertEquals(0L, pagina["totalElements"].asLong())
    }

    @Test
    fun `los seis filtros llegan al listado`() {
        val documento = documento()
        val otroDocumento = documento()
        val ventanilla1 = nuevaCaja()
        val ventanilla2 = nuevaCaja()
        val ana = cuenta("CAJERO")
        val luis = cuenta("CAJERO")
        val deAna = cobrar(ventanilla1, ana, documento)
        val deLuis = cobrar(ventanilla2, luis, documento)
        val otroDeAna = cobrar(ventanilla1, ana, otroDocumento)

        // por documento, del más reciente al más antiguo; cada fila con lo que la grilla pinta
        val porDocumento = listado("documento=$documento")
        assertEquals(listOf(deLuis, deAna), numeros(porDocumento))
        val fila = porDocumento["content"][1]
        assertEquals(
            listOf("numero_impreso", "emitido_en", "pagador_documento", "pagador_nombre", "total", "forma_pago", "duplicados", "estado"),
            fila.propertyNames().toList()
        )
        assertEquals(documento, fila["pagador_documento"].asString())
        assertEquals("FLORES OTINIANO JUNIOR", fila["pagador_nombre"].asString())
        assertEquals("150.50", fila["total"]["importe"].asString())
        assertEquals(hoy.toString(), fila["total"]["actualizado_a"].asString())
        assertEquals("EFECTIVO", fila["forma_pago"].asString())
        assertEquals(0L, fila["duplicados"].asLong())
        assertEquals(EMITIDO, fila["estado"].asString())
        assertEquals(hoy, OffsetDateTime.parse(fila["emitido_en"].asString()).atZoneSameInstant(LIMA).toLocalDate())
        // el documento se busca en mayúsculas, como se guarda
        assertEquals(listOf(deLuis, deAna), numeros(listado("documento=${documento.lowercase()}")))

        // por caja (su código) y por cajero (el correo, tal cual)
        assertEquals(listOf(otroDeAna, deAna), numeros(listado("caja=${ventanilla1.codigo}")))
        assertEquals(listOf(deLuis), numeros(listado("documento=$documento&caja=${ventanilla2.codigo}")))
        assertEquals(listOf(deLuis), numeros(listado("cajero=${luis.email}")))
        assertEquals(listOf(otroDeAna, deAna), numeros(listado("cajero=${ana.email}")))
        // una caja que no existe no tiene recibos
        assertEquals(emptyList<String>(), numeros(listado("caja=C-NO-EXISTE")))

        // por días de Lima
        assertEquals(listOf(deLuis, deAna), numeros(listado("documento=$documento&desde=$hoy&hasta=$hoy")))
        assertEquals(emptyList<String>(), numeros(listado("documento=$documento&desde=${hoy.plusDays(1)}")))
        assertEquals(emptyList<String>(), numeros(listado("documento=$documento&hasta=${hoy.minusDays(1)}")))

        // por estado
        anular(deLuis)
        assertEquals(listOf(deLuis), numeros(listado("documento=$documento&estado=ANULADO")))
        assertEquals(listOf(deAna), numeros(listado("documento=$documento&estado=emitido")))
    }

    @Test
    fun `el rango es de dias de Lima e incluye el dia entero del hasta`() {
        val documento = documento()
        val caja = nuevaCaja()
        // 23:59:59 en Lima son las 04:59:59 del día siguiente en UTC: un rango en días UTC lo dejaría fuera
        val antes = reciboEscrito(caja, 1, OffsetDateTime.parse("2026-03-14T23:59:59.999-05:00"), documento)
        val primero = reciboEscrito(caja, 2, OffsetDateTime.parse("2026-03-15T00:00:00-05:00"), documento)
        val ultimo = reciboEscrito(caja, 3, OffsetDateTime.parse("2026-03-15T23:59:59.999-05:00"), documento)
        val despues = reciboEscrito(caja, 4, OffsetDateTime.parse("2026-03-16T00:00:00-05:00"), documento)

        val delDia = listado("documento=$documento&desde=2026-03-15&hasta=2026-03-15")

        assertEquals(listOf("${caja.serie}-0000003", "${caja.serie}-0000002"), numeros(delDia))
        assertEquals(4, listOf(antes, primero, ultimo, despues).toSet().size)
        assertEquals(4, numeros(listado("documento=$documento&desde=2026-03-14&hasta=2026-03-16")).size)
    }

    @Test
    fun `el estado se deriva de la anulacion y se filtra`() {
        val documento = documento()
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val vigente = cobrar(caja, cajero, documento)
        val anulado = cobrar(caja, cajero, documento)

        anular(anulado)

        val filas = listado("documento=$documento")["content"].toList().associate { it["numero_impreso"].asString() to it["estado"].asString() }
        assertEquals(mapOf(vigente to EMITIDO, anulado to ANULADO), filas)
        assertEquals(listOf(anulado), numeros(listado("documento=$documento&estado=ANULADO")))
        assertEquals(listOf(vigente), numeros(listado("documento=$documento&estado=EMITIDO")))
    }

    @Test
    fun `los duplicados se cuentan en la fila y en la ficha`() {
        val documento = documento()
        val numero = cobrar(nuevaCaja(), cuenta("CAJERO"), documento)
        val supervisor = funcionario("SUPERVISOR_CAJA")

        repeat(2) { duplicado(numero, supervisor) }

        assertEquals(2L, listado("documento=$documento")["content"][0]["duplicados"].asLong())
        assertEquals(2L, ficha(numero)["duplicados"].asLong())
    }

    @Test
    fun `el orden no depende del tamano de pagina aunque dos recibos se emitan en el mismo instante`() {
        val documento = documento()
        val caja = nuevaCaja()
        val instante = OffsetDateTime.parse("2026-04-01T10:00:00.123456-05:00")
        (1L..3L).forEach { reciboEscrito(caja, it, instante, documento) }
        reciboEscrito(caja, 4, instante.minusSeconds(1), documento)

        val entera = numeros(listado("documento=$documento&size=4"))
        val deAUno = (0..3).flatMap { numeros(listado("documento=$documento&size=1&page=$it")) }
        val deADos = (0..1).flatMap { numeros(listado("documento=$documento&size=2&page=$it")) }

        assertEquals(4, entera.toSet().size, entera.toString())
        assertEquals(entera, deAUno)
        assertEquals(entera, deADos)
        // los del mismo instante, por id descendente; después, el anterior
        val ids = registros("recibo", "pagador_documento" to documento).associate { it["attributes"]["numero_impreso"].asString() to it["id"].asString() }
        assertEquals(entera.take(3).sortedByDescending { ids.getValue(it) }, entera.take(3))
        assertEquals("${caja.serie}-0000004", entera.last())
        val pagina = listado("documento=$documento&size=1&page=2")
        assertEquals(4L, pagina["totalElements"].asLong())
        assertEquals(4, pagina["totalPages"].asInt())
    }

    @Test
    fun `sin READ sobre recibo, 403`() {
        val sinRecibo = funcionario(listOf(permiso("caja", "READ"), permiso("anulacion_recibo", "READ")))

        send("GET", "$RECIBOS?documento=${documento()}", null, HttpStatus.FORBIDDEN, sinRecibo)
        // TESORERIA consulta
        send("GET", "$RECIBOS?documento=${documento()}", null, HttpStatus.OK, funcionario("TESORERIA"))
    }

    @Test
    fun `un rango al reves, una fecha mal escrita o un estado desconocido dan 400`() {
        rejected("GET", "$RECIBOS?desde=2026-03-16&hasta=2026-03-15", null, "hasta")
        rejected("GET", "$RECIBOS?estado=PAGADO", null, "estado")
        rejected("GET", "$RECIBOS?desde=15/03/2026", null, "desde")
    }

    // la ficha

    @Test
    fun `la ficha da el recibo con sus lineas, su estado, sus duplicados y su anulacion`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val numero = cobrar(caja, cajero, documento())

        val vigente = ficha(numero, funcionario("TESORERIA"))
        assertEquals(numero, vigente["numero_impreso"].asString())
        assertEquals(caja.codigo, vigente["caja"].asString())
        assertEquals(cajero.email, vigente["cajero"].asString())
        assertEquals("NORMAL", vigente["tipo_pago"].asString())
        assertEquals("150.50", vigente["total"]["importe"].asString())
        assertEquals(listOf("150.50"), vigente["lineas"].toList().map { it["monto"]["importe"].asString() })
        assertEquals(EMITIDO, vigente["estado"].asString())
        assertEquals(0L, vigente["duplicados"].asLong())
        assertTrue(vigente["anulacion"].isNull, vigente.toString())
        // el número se escribe como en el papel, o sin los ceros
        assertEquals(numero, ficha(numero.substringBefore('-').lowercase() + "-" + numero.substringAfter('-').trimStart('0'))["numero_impreso"].asString())

        val supervisor = cuenta("SUPERVISOR_CAJA")
        anular(numero, supervisor.token)

        val anulada = ficha(numero)
        assertEquals(ANULADO, anulada["estado"].asString())
        val anulacion = anulada["anulacion"]
        assertEquals(listOf("fecha", "motivo", "autorizado_por", "documento_autorizacion", "usuario"), anulacion.propertyNames().toList())
        assertEquals(hoy.toString(), anulacion["fecha"].asString())
        assertEquals("COBRO EN DEMASÍA", anulacion["motivo"].asString())
        assertEquals("JEFE DE CAJA", anulacion["autorizado_por"].asString())
        assertEquals("MEMO 12-2026", anulacion["documento_autorizacion"].asString())
        assertEquals(supervisor.email, anulacion["usuario"].asString())
        // las cifras siguen siendo las del papel
        assertEquals(vigente["lineas"], anulada["lineas"])
        assertEquals(vigente["total"], anulada["total"])
    }

    @Test
    fun `la ficha da 404 si el recibo no existe y 400 si el numero esta mal formado`() {
        send("GET", "$RECIBOS/ZZZZZ-9999999", null, HttpStatus.NOT_FOUND)
        rejected("GET", "$RECIBOS/abc", null, "numero_impreso")
        rejected("GET", "$RECIBOS/001-0000000", null, "numero_impreso")
    }

    // el duplicado

    @Test
    fun `el primer duplicado registra la reimpresion y va marcado`() {
        val numero = cobrar(nuevaCaja(), cuenta("CAJERO"), documento())
        val supervisor = cuenta("SUPERVISOR_CAJA")

        val texto = texto(duplicado(numero, supervisor.token))

        listOf(MUNICIPALIDAD, numero, "RECIBO DE CAJA · DUPLICADO N.° 1", "S/ 150.50").forEach { assertTrue(it in texto, "falta «$it» en:\n$texto") }
        assertFalse("ANULADO" in texto, texto)
        val reimpresion = reimpresiones(numero).single()["attributes"]
        assertEquals(hoy.toString(), reimpresion["fecha"].asString())
        assertEquals(supervisor.email, reimpresion["usuario"].asString())
        assertEquals("reimpresión pedida por el pagador", reimpresion["observacion"].asString())
        assertTrue(Regex("[0-9a-f]{64}").matches(reimpresion["resumen"].asString()), reimpresion.toString())
    }

    @Test
    fun `dos duplicados dan dos registros, numerados, con el mismo resumen`() {
        val numero = cobrar(nuevaCaja(), cuenta("CAJERO"), documento())
        val supervisor = funcionario("SUPERVISOR_CAJA")

        duplicado(numero, supervisor)
        val segundo = texto(duplicado(numero, supervisor))

        assertTrue("DUPLICADO N.° 2" in segundo, segundo)
        val registradas = reimpresiones(numero)
        assertEquals(2, registradas.size)
        assertEquals(1, registradas.map { it["attributes"]["resumen"].asString() }.toSet().size)
    }

    @Test
    fun `el duplicado de un recibo anulado lo dice`() {
        val numero = cobrar(nuevaCaja(), cuenta("CAJERO"), documento())
        anular(numero)

        val texto = texto(duplicado(numero, funcionario("SUPERVISOR_CAJA")))

        listOf("RECIBO ANULADO — no acredita pago", "COBRO EN DEMASÍA", "DUPLICADO N.° 1").forEach { assertTrue(it in texto, "falta «$it» en:\n$texto") }
    }

    @Test
    fun `si el recibo ya no se dibuja igual que en su primer duplicado, 409 y no se registra`() {
        val numero = cobrar(nuevaCaja(), cuenta("CAJERO"), documento())
        val supervisor = funcionario("SUPERVISOR_CAJA")
        duplicado(numero, supervisor)

        // nadie puede editar un recibo: ni un rol de caja, ni src/main (InmutabilidadDelReciboTest), ni un ADMIN por la api
        // de core (GuardiaDeEscrituras). para ver que el resumen muerde hay que cambiar de verdad un dato guardado: solo
        // aquí, en la base, que es lo que pasaría si alguien la tocara por fuera
        val recibo = registros("recibo", "numero_impreso" to numero).single()
        val atributos = json.convertValue(recibo["attributes"], Map::class.java) + ("pagador_nombre" to "OTRO NOMBRE")
        send("PUT", "/api/objects/recibo/records/${recibo["id"].asString()}", mapOf("attributes" to atributos), HttpStatus.FORBIDDEN)
        cambiarEnLaBase("recibo", recibo["id"].asString(), "pagador_nombre" to "OTRO NOMBRE")

        val problema = tree(send("POST", "$RECIBOS/$numero/duplicados", OBSERVACION, HttpStatus.CONFLICT, supervisor))
        assertTrue(problema["detail"].asString().contains("ya no se dibuja igual"), problema.toString())
        assertEquals(1, reimpresiones(numero).size)
    }

    @Test
    fun `el duplicado exige CREATE sobre reimpresion_recibo y su observacion`() {
        val numero = cobrar(nuevaCaja(), cuenta("CAJERO"), documento())

        val problema = tree(send("POST", "$RECIBOS/$numero/duplicados", OBSERVACION, HttpStatus.FORBIDDEN, funcionario("CAJERO")))
        assertTrue(problema["detail"].asString().contains("reimpresion_recibo"), problema.toString())
        val supervisor = funcionario("SUPERVISOR_CAJA")
        rejected("POST", "$RECIBOS/$numero/duplicados", mapOf("observacion" to " "), "observacion", supervisor)
        rejected("POST", "$RECIBOS/$numero/duplicados", OBSERVACION + ("formato" to "XLS"), "formato", supervisor)
        send("POST", "$RECIBOS/ZZZZZ-9999999/duplicados", OBSERVACION, HttpStatus.NOT_FOUND, supervisor)
        assertEquals(0, reimpresiones(numero).size)
    }

    // un documento nuevo, único en la base compartida
    private fun documento(): String = "7${unico().take(7)}"

    // cobra una orden de ese pagador en esa caja: el número impreso
    private fun cobrar(
        caja: CajaDePrueba,
        cajero: Cuenta,
        documento: String
    ): String {
        val ordenId = post("/api/caja/ordenes-de-cobro", orden("pagador_documento" to documento))["orden_id"].asString()
        val cobro =
            post(
                "/api/caja/cobros",
                mapOf("caja" to caja.codigo, "forma_pago" to "EFECTIVO", "ordenes" to listOf(ordenId), "observacion" to "cobro en ventanilla"),
                cajero.token
            )
        return cobro["recibo"]["numero_impreso"].asString()
    }

    private fun anular(
        numero: String,
        token: String = funcionario("SUPERVISOR_CAJA")
    ) = post(
        "$RECIBOS/$numero/anulacion",
        mapOf(
            "motivo" to "COBRO EN DEMASÍA",
            "autorizado_por" to "JEFE DE CAJA",
            "documento_autorizacion" to "MEMO 12-2026",
            "observacion" to "el pagador pagó dos veces"
        ),
        token
    )

    private fun listado(filtros: String): JsonNode = tree(send("GET", "$RECIBOS?$filtros", null, HttpStatus.OK, funcionario("TESORERIA")))

    private fun numeros(pagina: JsonNode): List<String> = pagina["content"].toList().map { it["numero_impreso"].asString() }

    private fun ficha(
        numero: String,
        token: String = this.token
    ): JsonNode = tree(send("GET", "$RECIBOS/$numero", null, HttpStatus.OK, token))

    // el pdf del duplicado: 201, application/pdf
    private fun duplicado(
        numero: String,
        token: String
    ): ByteArray =
        client
            .post()
            .uri("$RECIBOS/$numero/duplicados")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(OBSERVACION)
            .exchange()
            .expectStatus()
            .isCreated
            .expectHeader()
            .contentType(MediaType.APPLICATION_PDF)
            .expectBody(ByteArray::class.java)
            .returnResult()
            .responseBody!!

    private fun reimpresiones(numero: String): List<JsonNode> {
        val reciboId = registros("recibo", "numero_impreso" to numero).single()["id"].asString()
        return registros("reimpresion_recibo", "recibo" to reciboId)
    }

    private companion object {
        const val RECIBOS = "/api/caja/recibos"
        val OBSERVACION = mapOf("observacion" to "reimpresión pedida por el pagador")
    }
}
