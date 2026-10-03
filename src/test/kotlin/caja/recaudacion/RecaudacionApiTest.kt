package caja.recaudacion

import caja.CajaApiTest
import caja.buzon.SistemaDeOrigenFalso
import caja.comun.LIMA
import caja.turno.TurnoApiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// GET /api/caja/recaudacion/avance, /recaudacion/por-area y /conciliacion (ConciliacionDeNDiasTest,
// CobrarConElOrigenApagadoTest.LaConciliacion, CierreDeCajaJdbcTest DeLaDistribucion, DelDiaDelTurno y DeLaNoContencion,
// CierreYRecaudacionControllerTest y ElDiaDeLaVentanillaEsElDeLimaTest de caja). el sistema de origen es el falso del
// PR 8 (MockWebServer), que contesta la conciliación de cada día. el reloj de esta clase se mueve: cada prueba cobra en
// días suyos, lejos de hoy, porque la base es compartida y la recaudación y la conciliación de un día suman TODO lo de
// ese día. el buzón está apagado: los pagos se marcan ENTREGADO como admin, como lo haría el publicador
@TestPropertySource(properties = ["caja.buzon.timeout=2s"])
class RecaudacionApiTest : CajaApiTest() {
    @TestConfiguration
    class DeLaPrueba {
        @Bean
        @Primary
        fun relojMovible(): Clock = reloj

        // retiene el cierre en curso después de escribir su cierre_turno, con el candado del turno tomado y sin
        // confirmar (como CierreApiTest): solo lo arma la prueba de la no contención
        @Bean
        fun retieneElCierre() =
            object : RecordChangeListener {
                override suspend fun recordChanged(change: RecordChange) {
                    val retencion = retenido.get() ?: return
                    if (change.objectName == "cierre_turno" && change.kind == RecordChangeKind.CREATED) {
                        retencion.tomado.complete(Unit)
                        retencion.soltar.await()
                    }
                }
            }
    }

    class Retencion {
        val tomado = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
    }

    @AfterEach
    fun enHoraYSinRetener() {
        retenido.getAndSet(null)?.soltar?.complete(Unit)
        reloj.desfase = Duration.ZERO
    }

    private val hoy: LocalDate get() = LocalDate.now(reloj)

    // de la conciliación (ConciliacionDeNDiasTest)

    @Test
    fun `los ocho dias cuadran, cada uno con su fecha, y la linea trae las dos mitades`() {
        val dias = diasNuevos(8)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        dias.forEachIndexed { i, dia ->
            enElDia(dia)
            entregar(cobrarOrden(caja, cajero, OK, "100.00").pagoId)
            if (i == 0) {
                // el primer día, además, un cobro que se anula: entra y sale, y el origen solo aplica lo que quedó
                val anulado = cobrarOrden(caja, cajero, OK, "50.00")
                entregar(anulado.pagoId)
                entregar(anular(anulado.numero)["pago_anulado_id"].asString())
            }
            origen.conciliar(dia, 1, 1, 0, "100.00")
        }
        enElDia(dias.last().plusDays(1))

        val noCuadran =
            dias.mapNotNull { dia ->
                val conciliacion = conciliacion(dia)
                assertEquals(dia.toString(), conciliacion["fecha"].asString(), "toda cifra indica su fecha")
                assertEquals(hoy.toString(), conciliacion["a_la_fecha"].asString())
                if (conciliacion["cuadra"].asBoolean()) null else conciliacion.toString()
            }
        assertEquals(emptyList<String>(), noCuadran, "ocho días simulados de conciliación a cero")

        // las dos mitades: lo que la caja cobró (del buzón y de los recibos) y lo que el origen aplicó
        val linea = conciliacion(dias.first())["lineas"].single()
        assertEquals(OK, linea["sistema_destino"].asString())
        assertEquals(2, linea["registrados"].asInt())
        assertEquals(1, linea["anulados"].asInt())
        assertEquals(0, linea["en_transito"].asInt())
        assertEquals(0, linea["muertos"].asInt())
        assertEquals(0, linea["explicados"].asInt())
        assertEquals("150.00", cifra(linea["cobrado"], dias.first()))
        assertEquals("50.00", cifra(linea["anulado"], dias.first()))
        assertEquals("100.00", cifra(linea["neto"], dias.first()))
        assertEquals(1, linea["recibidos"].asInt())
        assertEquals(1, linea["aplicados"].asInt())
        assertEquals(0, linea["rechazados"].asInt())
        assertEquals("100.00", cifra(linea["importe_aplicado"], dias.first()))
        assertEquals("0.00", cifra(linea["diferencia"], dias.first()))
        assertTrue(linea["por_que_no_se_sabe"].isNull)
        assertTrue(linea["cuadra"].asBoolean())
        // se le preguntó al origen por ESE día, en su ruta
        assertTrue(origen.consultadas.any { it.first == "/pagos/conciliacion?fecha=${dias.first()}" }, origen.consultadas.toString())
    }

    @Test
    fun `un dia sin ningun cobro cuadra, y tiene razon en cuadrar`() {
        val dia = diasNuevos(1).single()

        val conciliacion = conciliacion(dia)

        assertEquals(0, conciliacion["lineas"].size())
        assertTrue(conciliacion["cuadra"].asBoolean())
    }

    @Test
    fun `deja de cuadrar si el origen aplico de menos, o si rechazo alguno aunque la cifra coincida`() {
        val (deMenos, conRechazo) = diasNuevos(2)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        listOf(deMenos, conRechazo).forEach { dia ->
            enElDia(dia)
            entregar(cobrarOrden(caja, cajero, OK, "100.00").pagoId)
        }
        origen.conciliar(deMenos, 1, 1, 0, "40.00")
        origen.conciliar(conRechazo, 2, 1, 1, "100.00")

        val aplicoDeMenos = conciliacion(deMenos)
        assertFalse(aplicoDeMenos["cuadra"].asBoolean())
        assertEquals("60.00", cifra(aplicoDeMenos["lineas"].single()["diferencia"], deMenos), "la caja cobró 100.00 y el origen dice 40.00")

        val rechazo = conciliacion(conRechazo)
        val linea = rechazo["lineas"].single()
        assertEquals("0.00", cifra(linea["diferencia"], conRechazo))
        assertEquals(1, linea["rechazados"].asInt())
        assertFalse(linea["cuadra"].asBoolean(), "uno espera a alguien en el origen: no cuadra por casualidad")
        assertFalse(rechazo["cuadra"].asBoolean())
    }

    @Test
    fun `un pago todavia en transito impide que el dia cuadre`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        cobrarOrden(nuevaCaja(), cuenta("CAJERO"), OK, "100.00")
        origen.conciliar(dia, 1, 1, 0, "100.00")

        val conciliacion = conciliacion(dia)

        val linea = conciliacion["lineas"].single()
        assertEquals(1, linea["en_transito"].asInt())
        assertEquals("0.00", cifra(linea["diferencia"], dia))
        assertFalse(linea["cuadra"].asBoolean())
        assertFalse(conciliacion["cuadra"].asBoolean())
    }

    @Test
    fun `con el origen apagado la linea no trae ceros sino su motivo, y con el destino sin configurar tampoco`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        entregar(cobrarOrden(caja, cajero, CAIDO, "200.00").pagoId)
        entregar(cobrarOrden(caja, cajero, SIN_URL, "75.50").pagoId)

        val conciliacion = conciliacion(dia)

        assertFalse(conciliacion["cuadra"].asBoolean(), "y el día entero no cuadra: es lo que cuesta un origen que no contesta")
        val lineas = conciliacion["lineas"].toList().associateBy { it["sistema_destino"].asString() }
        assertEquals(setOf(CAIDO, SIN_URL), lineas.keys)
        // ningún campo del origen vale 0 ni 0.00: todos van en null. se recorre el json entero de cada uno
        lineas.values.forEach { linea ->
            CAMPOS_DEL_ORIGEN.forEach { campo ->
                assertTrue(linea.has(campo), "$campo: $linea")
                assertEquals(emptyList<String>(), ceros(linea[campo]), "$campo: $linea")
                assertTrue(linea[campo].isNull, "$campo: $linea")
            }
            assertFalse(linea["cuadra"].asBoolean())
        }
        assertTrue(lineas.getValue(CAIDO)["por_que_no_se_sabe"].asString().startsWith("$CAIDO no contestó: "), lineas.getValue(CAIDO).toString())
        assertTrue(
            lineas.getValue(SIN_URL)["por_que_no_se_sabe"].asString().startsWith("el destino $SIN_URL no está configurado"),
            lineas.getValue(SIN_URL).toString()
        )
        // lo que la caja sabe sola lo dice igual: el origen caído deja la conciliación incompleta, no ciega
        assertEquals(1, lineas.getValue(CAIDO)["registrados"].asInt())
        assertEquals("200.00", cifra(lineas.getValue(CAIDO)["neto"], dia))
        assertEquals("75.50", cifra(lineas.getValue(SIN_URL)["cobrado"], dia))
    }

    @Test
    fun `la conciliacion exige el dia, sin fecha o mal escrita es 400 en fecha`() {
        rejected("GET", CONCILIACION, null, "fecha")
        rejected("GET", "$CONCILIACION?fecha=", null, "fecha")
        rejected("GET", "$CONCILIACION?fecha=02/10/2026", null, "fecha")
    }

    // de la distribución (CierreDeCajaJdbcTest.DeLaDistribucion)

    @Test
    fun `las partes suman el total por origen y por area, lo de una orden no tiene partida y se dice aparte`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        // 33.33 y 33.34: si en algún sitio hubiera un reparto proporcional, aquí saldría un céntimo huérfano
        val (una, otra) = codigoDeTasa() to codigoDeTasa()
        val areaDeUna = areaDe(nuevaTasa(una, "33.33", dia.minusDays(1)))
        nuevaTasa(otra, "33.34", dia.minusDays(1))
        cobrarOrden(caja, cajero, OK, "100.00")
        cobrarTasa(caja, cajero, una)
        cobrarTasa(caja, cajero, otra)

        val avance = avance("desde=$dia&hasta=$dia")
        assertEquals(dia.toString(), avance["desde"].asString())
        assertEquals(dia.toString(), avance["hasta"].asString())
        assertEquals(hoy.toString(), avance["a_la_fecha"].asString())
        assertEquals(listOf("$OK 100.00 0.00 100.00", "TASA 66.67 0.00 66.67"), filas(avance, "origen"))
        assertEquals("166.67", cifra(avance["neto"], hoy))
        assertEquals("166.67", cifra(avance["cobrado"], hoy))
        assertEquals("0.00", cifra(avance["anulado"], hoy))
        assertEquals(sumaDeLosNetos(avance), BigDecimal(cifra(avance["neto"], hoy)))
        assertTrue(avance["turno"].isNull)

        val porArea = porArea("desde=$dia&hasta=$dia")
        assertEquals(cifra(avance["neto"], hoy), cifra(porArea["neto"], hoy), "la distribución reparte filas: suma exactamente lo mismo")
        assertEquals(sumaDeLosNetos(porArea), BigDecimal(cifra(porArea["neto"], hoy)))
        assertEquals("100.00", cifra(porArea["neto_sin_partida"], hoy), "lo que no tiene partida se dice, no se esconde")
        val deLaOrden = porArea["filas"].toList().single { it["concepto"].asString() == OK }
        listOf("area", "area_nombre", "partida").forEach { assertTrue(deLaOrden[it].isNull, "$it: $deLaOrden") }
        val deLaTasa = porArea["filas"].toList().single { it["concepto"].asString() == una }
        assertEquals(areaDeUna, deLaTasa["area"].asString())
        assertEquals("ÁREA DE LA PRUEBA", deLaTasa["area_nombre"].asString())
        assertEquals("1.3.1.1.1.1", deLaTasa["partida"].asString())
        assertEquals("33.33", cifra(deLaTasa["neto"], hoy))

        // el filtro por origen
        assertEquals(listOf("TASA 66.67 0.00 66.67"), filas(avance("desde=$dia&hasta=$dia&origen=tasa"), "origen"))
    }

    @Test
    fun `filtrar por area deja fuera lo de las ordenes, con el codigo o con la etiqueta del desplegable`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        val area = areaDe(nuevaTasa(codigo, "33.33", dia.minusDays(1)))
        cobrarOrden(caja, cajero, OK, "70.00")
        cobrarTasa(caja, cajero, codigo, 3)

        listOf(area, "$area — ÁREA DE LA PRUEBA").forEach { filtro ->
            val soloEsa = porArea("desde=$dia&hasta=$dia&area=$filtro")
            assertEquals(1, soloEsa["filas"].size(), soloEsa.toString())
            assertEquals("99.99", cifra(soloEsa["neto"], hoy))
            assertEquals("0.00", cifra(soloEsa["neto_sin_partida"], hoy))
        }
        assertEquals(0, porArea("desde=$dia&hasta=$dia&area=NO-EXISTE")["filas"].size())
    }

    @Test
    fun `una anulacion se resta del avance en vez de desaparecer de el`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        val cobro = cobrarOrden(nuevaCaja(), cuenta("CAJERO"), OK, "220.00")
        anular(cobro.numero)

        val avance = avance("desde=$dia&hasta=$dia")

        assertEquals(listOf("$OK 220.00 220.00 0.00"), filas(avance, "origen"))
        assertEquals("220.00", cifra(avance["cobrado"], hoy))
        assertEquals("220.00", cifra(avance["anulado"], hoy))
        assertEquals("0.00", cifra(avance["neto"], hoy), "entró y salió: el avance lo cuenta y lo resta, no lo esconde")
        val porArea = porArea("desde=$dia&hasta=$dia")
        assertEquals(
            listOf("220.00 220.00 0.00"),
            porArea["filas"].toList().map {
                "${cifra(it["cobrado"], hoy)} ${cifra(it["anulado"], hoy)} ${cifra(it["neto"], hoy)}"
            }
        )
    }

    @Test
    fun `una linea forjada en la base no entra en la recaudacion`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", dia.minusDays(1))
        val numero = cobrarTasa(caja, cajero, codigo)
        val recibo = registros("recibo", "numero_impreso" to numero).single()["id"].asString()
        // la API genérica ya no la escribe (GuardiaDeEscrituras); quien escribe en la base, sí
        forjarEnLaBase("linea_recibo", mapOf("recibo" to recibo, "concepto" to "FORJADA", "sistema_origen" to "forjado", "monto" to "999.00"))

        val avance = avance("desde=$dia&hasta=$dia")
        val porArea = porArea("desde=$dia&hasta=$dia")

        assertEquals(listOf("TASA 12.30 0.00 12.30"), filas(avance, "origen"))
        assertEquals("12.30", cifra(porArea["neto"], hoy), "la línea forjada lleva otro sello: no es del cobro")
        assertEquals(1, porArea["filas"].size(), porArea.toString())
    }

    // un recibo roto en el rango (un total negativo escrito en la base) no tumba con un 500 el
    // reporte de todo el rango: queda fuera de las cifras y se nombra con su porqué, en las dos rutas
    @Test
    fun `un recibo roto no tumba el avance ni la recaudacion por area, queda fuera y se nombra`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", dia.minusDays(1))
        cobrarTasa(caja, cajero, codigo)
        reciboEscrito(caja, 9_999_999, dia.atTime(11, 0).atZone(LIMA).toOffsetDateTime(), "12345678", tipoPago = "TASA", total = "-20.00")
        val roto = "${caja.serie}-9999999"

        val avance = avance("desde=$dia&hasta=$dia")
        val porArea = porArea("desde=$dia&hasta=$dia")

        assertEquals(listOf("TASA 12.30 0.00 12.30"), filas(avance, "origen"))
        assertEquals("12.30", cifra(porArea["neto"], hoy))
        listOf(avance, porArea).forEach { respuesta ->
            val nombrado = respuesta["recibos_con_datos_rotos"].single()
            assertEquals(roto, nombrado["numero_impreso"].asString())
            assertTrue(nombrado["motivo"].asString().contains("-20.00"), nombrado.toString())
        }
    }

    // del día del turno (CierreDeCajaJdbcTest.DelDiaDelTurno y ElDiaDeLaVentanillaEsElDeLimaTest)

    @Test
    fun `un cobro nocturno va al turno de su dia en Lima, y el rango se aplica sobre la fecha del turno`() {
        val dia = diasNuevos(2).first()
        enElDia(dia, LocalTime.of(21, 30))
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val numero = cobrarOrden(caja, cajero, OK, "310.00").numero

        // la premisa: el instante del recibo ya es del día siguiente en UTC, y su turno es de su día en Lima
        val recibo = registros("recibo", "numero_impreso" to numero).single()["attributes"]
        assertEquals(dia.plusDays(1), Instant.parse(recibo["emitido_en"].asString()).atZone(ZoneOffset.UTC).toLocalDate())
        val turno = registros("turno", "caja" to caja.id).single()["attributes"]
        assertEquals(dia.toString(), turno["fecha"].asString())

        assertEquals(listOf("$OK 310.00 0.00 310.00"), filas(avance("desde=$dia&hasta=$dia"), "origen"))
        assertEquals(0, avance("desde=${dia.plusDays(1)}&hasta=${dia.plusDays(1)}")["filas"].size(), "no aparece en el día siguiente")
        assertEquals("310.00", cifra(porArea("desde=$dia&hasta=$dia")["neto"], hoy))
    }

    @Test
    fun `la cifra del dia cuadra con el arqueo en vivo de su turno, dos consultas y un solo numero`() {
        val dia = diasNuevos(1).single()
        enElDia(dia, LocalTime.of(21, 30))
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", dia.minusDays(1))
        cobrarOrden(caja, cajero, OK, "120.00")
        cobrarOrden(caja, cajero, OK, "80.50", "TARJETA")
        anular(cobrarTasa(caja, cajero, codigo))

        val avance = avance("desde=$dia&hasta=$dia&caja=${caja.codigo}&cajero=${cajero.email}")

        assertEquals("212.80", cifra(avance["cobrado"], hoy), "la premisa: si saliera cero, lo de abajo cuadraría con dos ceros")
        val turno = avance["turno"]
        val turnoId = registros("turno", "caja" to caja.id).single()["id"].asString()
        assertEquals(turnoId, turno["turno_id"].asString())
        assertEquals(caja.codigo, turno["caja"].asString())
        assertEquals(cajero.email, turno["cajero"].asString())
        assertEquals(dia.toString(), turno["fecha"].asString())
        assertEquals("ABIERTO", turno["estado_del_turno"].asString())
        val arqueo = turno["arqueo"]
        assertEquals(cifra(avance["cobrado"], hoy), cifra(arqueo["total_cobrado"], hoy))
        assertEquals(cifra(avance["anulado"], hoy), cifra(arqueo["total_anulado"], hoy))
        assertEquals(cifra(avance["neto"], hoy), cifra(arqueo["neto"], hoy))
        assertTrue(arqueo["cuadra"].isNull && arqueo["total_declarado"].isNull, "en vivo nadie ha contado: $arqueo")
        // y es el mismo arqueo que GET /turnos/{id}/arqueo
        val delTurno = tree(send("GET", "/api/caja/turnos/$turnoId/arqueo", null, HttpStatus.OK))
        assertEquals(delTurno["arqueo"], arqueo)
    }

    @Test
    fun `con caja y cajero sin turno de hoy es 404, y un rango al reves o mal escrito es 400`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        val (suya, otra) = nuevaCaja() to nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", dia.minusDays(1))
        cobrarTasa(suya, cajero, codigo)

        // la caja existe y el cajero cobró hoy, pero en otra: no hay turno que arquear
        val problema = tree(send("GET", "$AVANCE?caja=${otra.codigo}&cajero=${cajero.email}", null, HttpStatus.NOT_FOUND))
        assertTrue(problema["detail"].asString().contains(cajero.email), problema.toString())
        rejected("GET", "$AVANCE?desde=2026-03-31&hasta=2026-03-01", null, "hasta")
        rejected("GET", "$AVANCE?desde=31/03/2026", null, "desde")
        rejected("GET", "$POR_AREA?desde=2026-03-31&hasta=2026-03-01", null, "hasta")
        rejected("GET", "$POR_AREA?hasta=marzo", null, "hasta")
    }

    @Test
    fun `una caja o un cajero que no existen son 400 en su campo, no un avance en cero`() {
        val dia = diasNuevos(1).single()
        val caja = nuevaCaja()

        val sinCaja = rejected("GET", "$AVANCE?desde=$dia&hasta=$dia&caja=NO-EXISTE", null, "caja")
        assertTrue(sinCaja["errors"][0]["message"].asString().contains("NO-EXISTE"), sinCaja.toString())
        val sinCajero = rejected("GET", "$AVANCE?desde=$dia&hasta=$dia&cajero=nadie@caja.test", null, "cajero")
        assertTrue(sinCajero["errors"][0]["message"].asString().contains("nadie@caja.test"), sinCajero.toString())
        // los dos a la vez, juntos; y con la caja que sí existe solo falla el cajero
        val ambos = tree(send("GET", "$AVANCE?caja=NO-EXISTE&cajero=nadie@caja.test", null, HttpStatus.BAD_REQUEST))
        assertEquals(listOf("caja", "cajero"), ambos["errors"].toList().map { it["field"].asString() })
        rejected("GET", "$AVANCE?caja=${caja.codigo}&cajero=nadie@caja.test", null, "cajero")
    }

    // de las páginas: core lee de a 200 y ordena por created_at, que las líneas de un recibo comparten

    @Test
    fun `cada linea se cuenta una sola vez aunque las de un recibo queden a los dos lados de una pagina`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        // 23 tasas de 1.01 a 1.23, y 40 recibos con las 23, cobrados a la vez en 8 cajas: 920 líneas, cinco páginas de
        // 200. las 23 líneas de un recibo comparten su created_at (el sello de su transacción), y los cobros simultáneos
        // las dejan intercaladas en la tabla: un ORDER BY created_at sin desempate puede repetir unas y saltarse otras
        // entre una página y la siguiente. cada precio es distinto, así que una línea repetida u omitida se nota
        val precios = (1..23).map { BigDecimal(100 + it).movePointLeft(2) }
        val codigos = precios.map { precio -> codigoDeTasa().also { nuevaTasa(it, precio.toPlainString(), dia.minusDays(1)) } }
        val ventanillas = (1..8).map { nuevaCaja() to cuenta("CAJERO") }
        val hilos = Executors.newFixedThreadPool(ventanillas.size)
        try {
            ventanillas
                .map { (caja, cajero) -> hilos.submit { repeat(5) { cobrarTasas(caja, cajero, codigos) } } }
                .forEach { it.get(300, TimeUnit.SECONDS) }
        } finally {
            hilos.shutdownNow()
        }
        val recibos = ventanillas.flatMap { (caja, _) -> registros("recibo", "caja" to caja.id) }
        assertEquals(40, recibos.size, "la premisa: 40 recibos")
        assertEquals(920, recibos.sumOf { registros("linea_recibo", "recibo" to it["id"].asString()).size }, "la premisa: 920 líneas")

        val avance = avance("desde=$dia&hasta=$dia")
        val porArea = porArea("desde=$dia&hasta=$dia")

        assertEquals(listOf("TASA 1030.40 0.00 1030.40"), filas(avance, "origen"))
        assertEquals("1030.40", cifra(porArea["neto"], hoy), "la distribución suma exactamente lo mismo que el avance")
        assertEquals(
            codigos.zip(precios).map { (codigo, precio) -> "$codigo ${precio.multiply(BigDecimal(40)).toPlainString()}" }.sorted(),
            porArea["filas"].toList().map { "${it["concepto"].asString()} ${cifra(it["cobrado"], hoy)}" }.sorted(),
            "cada línea, una sola vez"
        )
    }

    // del cliente del origen: el token, su tachadura y el origen colgado

    @Test
    fun `el token va en la cabecera de la consulta, no sale en el motivo, y un origen colgado agota su timeout`() {
        val (conToken, conEco, colgado) = diasNuevos(3)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        listOf(conToken to TOKEN_SISTEMA, conEco to TOKEN_SISTEMA, colgado to LENTO).forEach { (dia, sistema) ->
            enElDia(dia)
            entregar(cobrarOrden(caja, cajero, sistema, "100.00").pagoId)
        }
        origen.conciliar(conToken, 1, 1, 0, "100.00")
        // el eco del token en las formas que tacha el patrón (cabecera, Bearer, token=) y suelto, que solo tacha el token
        // configurado
        origen.conciliar(conEco, 503, """{"detail":"no autorizado: $TOKEN","eco":"Authorization: Bearer $TOKEN","token":"$TOKEN"}""")
        origen.conciliar(
            colgado,
            200,
            """{"recibidos":1,"aplicados":1,"rechazados":0,"importe_aplicado":"100.00"}""",
            demora = Duration.ofSeconds(8)
        )

        assertTrue(conciliacion(conToken)["cuadra"].asBoolean())
        val consulta = origen.consultadas.single { it.first == "/con-token/pagos/conciliacion?fecha=$conToken" }
        assertEquals("Bearer $TOKEN", consulta.second)

        val eco = conciliacion(conEco)["lineas"].single()
        val motivo = eco["por_que_no_se_sabe"].asString()
        assertTrue(motivo.startsWith("$TOKEN_SISTEMA no contestó: ") && motivo.contains("503") && motivo.contains("no autorizado"), motivo)
        assertFalse(motivo.contains(TOKEN), "el token no sale en el motivo: $motivo")
        assertFalse(eco.toString().contains(TOKEN), eco.toString())

        val antes = Instant.now()
        val lento = conciliacion(colgado)["lineas"].single()
        val espera = Duration.between(antes, Instant.now())
        assertTrue(espera < Duration.ofSeconds(6), "esperó el timeout (2 s), no la respuesta (8 s): $espera")
        assertTrue(lento["por_que_no_se_sabe"].asString().startsWith("$LENTO no contestó: "), lento.toString())
        assertTrue(lento["por_que_no_se_sabe"].asString().contains("Timeout"), lento.toString())
        CAMPOS_DEL_ORIGEN.forEach { assertTrue(lento[it].isNull, "$it: $lento") }
    }

    // de la no contención (CierreDeCajaJdbcTest.DeLaNoContencion)

    @Test
    fun `el avance no espera al candado de un cierre en curso`() {
        val dia = diasNuevos(1).single()
        enElDia(dia)
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "250.00", dia.minusDays(1))
        cobrarTasa(caja, cajero, codigo)

        val retencion = Retencion().also { retenido.set(it) }
        val hilos = Executors.newFixedThreadPool(2)
        try {
            val cierre =
                hilos.submit<Pair<HttpStatus, String>> {
                    exchange("POST", "/api/caja/turnos/cierre", mapOf("caja" to caja.codigo, "observacion" to "cierre en curso"), cajero.token)
                }
            runBlocking { withTimeout(30_000) { retencion.tomado.await() } }

            // el cierre tiene el candado del turno y no lo suelta: el avance, con el turno en vivo, contesta igual
            val avance =
                hilos.submit<Pair<HttpStatus, String>> {
                    exchange("GET", "$AVANCE?desde=$dia&hasta=$dia&caja=${caja.codigo}&cajero=${cajero.email}", null)
                }
            val (estado, cuerpo) = avance.get(5, TimeUnit.SECONDS)
            assertEquals(HttpStatus.OK, estado, cuerpo)
            assertEquals("250.00", cifra(tree(cuerpo)["turno"]["arqueo"]["neto"], hoy))
            assertFalse(cierre.isDone, "el cierre seguía con el candado mientras el avance contestaba")

            retencion.soltar.complete(Unit)
            assertEquals(HttpStatus.CREATED, cierre.get(60, TimeUnit.SECONDS).first)
        } finally {
            retencion.soltar.complete(Unit)
            hilos.shutdownNow()
        }
    }

    // de los permisos

    @Test
    fun `TESORERIA lee las tres rutas y SISTEMA_ORIGEN recibe 403`() {
        val tesoreria = funcionario("TESORERIA")
        val sistema = funcionario("SISTEMA_ORIGEN")
        val dia = diasNuevos(1).single()
        listOf("$AVANCE?desde=$dia&hasta=$dia", "$POR_AREA?desde=$dia&hasta=$dia", "$CONCILIACION?fecha=$dia").forEach { ruta ->
            send("GET", ruta, null, HttpStatus.OK, tesoreria)
            val problema = tree(send("GET", ruta, null, HttpStatus.FORBIDDEN, sistema))
            assertTrue(problema["detail"].asString().contains("permiso de lectura"), "$ruta: $problema")
        }
    }

    // ayudas

    private class Cobro(
        val numero: String,
        val pagoId: String
    )

    // días que nadie más usa en la base compartida: cuántos, seguidos
    private fun diasNuevos(cuantos: Int): List<LocalDate> {
        val primero = LocalDate.now(LIMA).plusDays(BASE + SIGUIENTE.getAndAdd(cuantos + 10).toLong())
        return (0 until cuantos).map { primero.plusDays(it.toLong()) }
    }

    // el reloj de caja, a esa hora de Lima de ese día
    private fun enElDia(
        dia: LocalDate,
        hora: LocalTime = LocalTime.of(10, 0)
    ) {
        reloj.desfase = Duration.between(Instant.now(), dia.atTime(hora).atZone(LIMA).toInstant())
    }

    private fun cobrarOrden(
        caja: CajaDePrueba,
        cajero: Cuenta,
        sistema: String,
        importe: String,
        forma: String = "EFECTIVO"
    ): Cobro {
        val ordenId = post(ORDENES, orden("sistema_origen" to sistema, "importe" to importe))["orden_id"].asString()
        val cobro =
            post(
                COBROS,
                mapOf("caja" to caja.codigo, "forma_pago" to forma, "ordenes" to listOf(ordenId), "observacion" to "cobro en ventanilla"),
                cajero.token
            )
        return Cobro(cobro["recibo"]["numero_impreso"].asString(), cobro["pago_id"].asString())
    }

    // el número del recibo
    private fun cobrarTasa(
        caja: CajaDePrueba,
        cajero: Cuenta,
        codigo: String,
        cantidad: Int = 1
    ): String =
        post(
            TASAS,
            mapOf(
                "caja" to caja.codigo,
                "forma_pago" to "EFECTIVO",
                "conceptos" to listOf(mapOf("codigo" to codigo, "cantidad" to cantidad)),
                "observacion" to "cobro de tasas en ventanilla"
            ),
            cajero.token
        )["recibo"]["numero_impreso"].asString()

    // un recibo de tasas con una línea por código
    private fun cobrarTasas(
        caja: CajaDePrueba,
        cajero: Cuenta,
        codigos: List<String>
    ) = post(
        TASAS,
        mapOf(
            "caja" to caja.codigo,
            "forma_pago" to "EFECTIVO",
            "conceptos" to codigos.map { mapOf("codigo" to it, "cantidad" to 1) },
            "observacion" to "cobro de tasas en ventanilla"
        ),
        cajero.token
    )

    // la anulación del recibo, por un supervisor: el acta, con el pago_anulado_id de su PAGO_ANULADO (null en un recibo
    // de tasas)
    private fun anular(numero: String): JsonNode = post("/api/caja/recibos/$numero/anulacion", ANULACION, funcionario("SUPERVISOR_CAJA"))

    // lo que haría el publicador del buzón con ese pago
    private fun entregar(pagoId: String) {
        val evento = registros("pago_evento", "evento_id" to pagoId).single()
        cambiarEnLaBase("pago_evento", evento["id"].asString(), "estado" to "ENTREGADO", "entregado_en" to Instant.now().toString())
    }

    // el código del área de una tasa
    private fun areaDe(tasaId: String): String {
        val area = tree(send("GET", "/api/objects/tasa/records/$tasaId", null, HttpStatus.OK))["attributes"]["area"].asString()
        return tree(send("GET", "/api/objects/area/records/$area", null, HttpStatus.OK))["attributes"]["codigo"].asString()
    }

    private fun conciliacion(dia: LocalDate): JsonNode = tree(send("GET", "$CONCILIACION?fecha=$dia", null, HttpStatus.OK))

    private fun avance(consulta: String): JsonNode = tree(send("GET", "$AVANCE?$consulta", null, HttpStatus.OK))

    private fun porArea(consulta: String): JsonNode = tree(send("GET", "$POR_AREA?$consulta", null, HttpStatus.OK))

    // cada fila como «<clave> cobrado anulado neto»
    private fun filas(
        respuesta: JsonNode,
        clave: String
    ): List<String> =
        respuesta["filas"].toList().map {
            "${it[clave].asString()} ${cifra(it["cobrado"], hoy)} ${cifra(it["anulado"], hoy)} ${cifra(it["neto"], hoy)}"
        }

    private fun sumaDeLosNetos(respuesta: JsonNode): BigDecimal =
        respuesta["filas"]
            .toList()
            .map {
                BigDecimal(it["neto"]["importe"].asString())
            }.fold(BigDecimal.ZERO, BigDecimal::add)

    // la cifra de un Importe, que tiene que llevar su fecha
    private fun cifra(
        importe: JsonNode,
        fecha: LocalDate
    ): String {
        assertFalse(importe.isNull, "una cifra sin valor")
        assertEquals(fecha.toString(), importe["actualizado_a"].asString(), "toda cifra lleva su fecha: $importe")
        return importe["importe"].asString()
    }

    // cada hoja del json que vale cero, con su ruta: un 0, un "0", un "0.00"
    private fun ceros(
        nodo: JsonNode?,
        ruta: String = "$"
    ): List<String> =
        when {
            nodo == null || nodo.isNull -> emptyList()
            nodo.isObject -> nodo.properties().flatMap { (clave, valor) -> ceros(valor, "$ruta.$clave") }
            nodo.isArray -> nodo.toList().flatMapIndexed { i, valor -> ceros(valor, "$ruta[$i]") }
            nodo.isNumber && nodo.decimalValue().signum() == 0 -> listOf(ruta)
            nodo.isString && nodo.asString().toBigDecimalOrNull()?.signum() == 0 -> listOf(ruta)
            else -> emptyList()
        }

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val COBROS = "/api/caja/cobros"
        const val TASAS = "/api/caja/cobros/tasas"
        const val AVANCE = "/api/caja/recaudacion/avance"
        const val POR_AREA = "/api/caja/recaudacion/por-area"
        const val CONCILIACION = "/api/caja/conciliacion"
        const val OK = "conc-ok"
        const val CAIDO = "conc-caido"
        const val SIN_URL = "conc-sin-url"
        const val TOKEN_SISTEMA = "conc-token"
        const val LENTO = "conc-lento"
        const val TOKEN = "el-token-de-la-conciliacion"
        val CAMPOS_DEL_ORIGEN = listOf("recibidos", "aplicados", "rechazados", "importe_aplicado", "diferencia")
        val ANULACION = mapOf("motivo" to "COBRO EN DEMASÍA", "observacion" to "el pagador pagó dos veces en ventanilla")

        val reloj = TurnoApiTest.RelojMovible()
        val retenido = AtomicReference<Retencion?>(null)
        val origen = SistemaDeOrigenFalso()

        // los días de esta clase empiezan lejos de hoy (entre 30 y 80 años), en un sitio distinto en cada corrida, y
        // cada pedido toma los suyos, separados
        val BASE = 11_000L + (0L..18_000L).random()
        val SIGUIENTE = AtomicInteger(0)

        @JvmStatic
        @DynamicPropertySource
        fun destinos(registro: DynamicPropertyRegistry) {
            registro.add("caja.buzon.destinos.$OK.url") { origen.url() }
            // nadie escucha en el puerto 1: la conexión se rechaza
            registro.add("caja.buzon.destinos.$CAIDO.url") { "http://127.0.0.1:1" }
            registro.add("caja.buzon.destinos.$TOKEN_SISTEMA.url") { origen.url("con-token") }
            registro.add("caja.buzon.destinos.$TOKEN_SISTEMA.token") { TOKEN }
            registro.add("caja.buzon.destinos.$LENTO.url") { origen.url("lento") }
        }

        @JvmStatic
        @AfterAll
        fun apagar() = origen.close()
    }
}
