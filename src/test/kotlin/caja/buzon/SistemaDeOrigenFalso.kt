package caja.buzon

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

// el buzón de entrada de un sistema de origen, por http de verdad (MockWebServer): contesta a cada pago lo que la
// prueba le diga por su pagoId (503 si no le dijo nada) y guarda lo que recibió. alRecibir corre mientras el
// publicador espera la respuesta: ahí se mira la base desde otra conexión. y contesta la conciliación del día (GET
// /pagos/conciliacion?fecha=) con lo que la prueba le diga que aplicó esa fecha (404 si no le dijo nada), y guarda cada
// consulta. y la comprobación en línea (POST /ordenes/vigencia, caja-backend#42): contesta de cada referencia lo que la
// prueba le diga con vigencia (y calla las que no), guarda cada pregunta, y bajo la raíz /ilegible contesta algo que no
// es json
class SistemaDeOrigenFalso : AutoCloseable {
    private val servidor = MockWebServer()
    private val respuestas = ConcurrentHashMap<String, Pair<Int, String>>()
    val recibidas = CopyOnWriteArrayList<Recibida>()
    private val conciliaciones = ConcurrentHashMap<String, Pair<Int, String>>()
    private val demoras = ConcurrentHashMap<String, Duration>()

    // cada GET que llegó, con su ruta y su consulta, y la cabecera Authorization
    val consultadas = CopyOnWriteArrayList<Pair<String, String?>>()

    // cada pregunta de vigencia que llegó, con su cuerpo
    val preguntas = CopyOnWriteArrayList<String>()
    private val vigencias = ConcurrentHashMap<String, String>()

    @Volatile
    var alRecibir: (String) -> Unit = {}

    class Recibida(
        val ruta: String,
        val autorizacion: String?,
        val tipo: String?,
        val cuerpo: String,
        val pagoId: String?
    )

    init {
        servidor.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return conciliacion(request)
                    if (request.url.encodedPath.endsWith(RUTA_VIGENCIA)) return vigencia(request)
                    val cuerpo = request.body?.utf8().orEmpty()
                    val pagoId = runCatching { JSON.readTree(cuerpo)["pagoId"]?.asString() }.getOrNull()
                    recibidas += Recibida(request.url.encodedPath, request.headers["Authorization"], request.headers["Content-Type"], cuerpo, pagoId)
                    pagoId?.let { alRecibir(it) }
                    val (estado, respuesta) = respuestas[pagoId] ?: (503 to "")
                    return MockResponse
                        .Builder()
                        .code(estado)
                        .setHeader("Content-Type", "application/json")
                        .body(respuesta)
                        .build()
                }
            }
        servidor.start()
    }

    // la raíz del sistema: el publicador llama a {url}/pagos
    fun url(raiz: String = ""): String = servidor.url("/$raiz").toString().removeSuffix("/")

    fun contestar(
        pagoId: String,
        estado: Int,
        cuerpo: String = ""
    ) {
        respuestas[pagoId] = estado to cuerpo
    }

    // lo que este origen dice haber aplicado esa fecha, como lo pide la conciliación: las cuatro cifras, el importe en
    // cadena
    fun conciliar(
        fecha: LocalDate,
        recibidos: Int,
        aplicados: Int,
        rechazados: Int,
        importeAplicado: String
    ) = conciliar(
        fecha,
        200,
        """{"fecha":"$fecha","recibidos":$recibidos,"aplicados":$aplicados,"rechazados":$rechazados,"importe_aplicado":"$importeAplicado"}"""
    )

    // demora: cuánto tarda en empezar a contestar, para el origen colgado
    fun conciliar(
        fecha: LocalDate,
        estado: Int,
        cuerpo: String,
        demora: Duration = Duration.ZERO
    ) {
        conciliaciones[fecha.toString()] = estado to cuerpo
        demoras[fecha.toString()] = demora
    }

    private fun conciliacion(request: RecordedRequest): MockResponse {
        consultadas += "${request.url.encodedPath}?${request.url.encodedQuery}" to request.headers["Authorization"]
        val fecha = request.url.queryParameter("fecha")
        val (estado, cuerpo) = conciliaciones[fecha] ?: (404 to "")
        return MockResponse
            .Builder()
            .headersDelay(demoras[fecha]?.toMillis() ?: 0, TimeUnit.MILLISECONDS)
            .code(estado)
            .setHeader("Content-Type", "application/json")
            .body(cuerpo)
            .build()
    }

    // lo que el origen dice de una referencia cuando caja le pregunta si sigue vigente
    fun vigencia(
        referencia: String,
        vigente: Boolean,
        importe: String? = null,
        motivo: String? = null
    ) {
        vigencias[referencia] =
            JSON.writeValueAsString(linkedMapOf("referencia_externa" to referencia, "vigente" to vigente, "importe" to importe, "motivo" to motivo))
    }

    // las preguntas que nombran esa referencia
    fun preguntasPor(referencia: String): List<String> = preguntas.filter { it.contains(referencia) }

    private fun vigencia(request: RecordedRequest): MockResponse {
        val cuerpo = request.body?.utf8().orEmpty()
        preguntas += cuerpo
        val respuesta =
            if (request.url.encodedPath.startsWith("/ilegible")) {
                "<html>no es json</html>"
            } else {
                val pedidas: List<String> =
                    runCatching { JSON.readTree(cuerpo)["ordenes"].toList().map { it["referencia_externa"].asString() } }
                        .getOrDefault(emptyList())
                val dichas: List<String> = pedidas.mapNotNull { vigencias[it] }
                "{\"ordenes\":[" + dichas.joinToString(",") + "]}"
            }
        return MockResponse
            .Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(respuesta)
            .build()
    }

    fun de(pagoId: String): List<Recibida> = recibidas.filter { it.pagoId == pagoId }

    override fun close() = servidor.close()

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        const val RUTA_VIGENCIA = "/ordenes/vigencia"
    }
}
