package caja.buzon

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// el buzón de entrada de un sistema de origen, por http de verdad (MockWebServer): contesta a cada pago lo que la
// prueba le diga por su pagoId (503 si no le dijo nada) y guarda lo que recibió. alRecibir corre mientras el
// publicador espera la respuesta: ahí se mira la base desde otra conexión. y contesta la conciliación del día (GET
// /pagos/conciliacion?fecha=) con lo que la prueba le diga que aplicó esa fecha (404 si no le dijo nada), y guarda cada
// consulta
class SistemaDeOrigenFalso : AutoCloseable {
    private val servidor = MockWebServer()
    private val respuestas = ConcurrentHashMap<String, Pair<Int, String>>()
    val recibidas = CopyOnWriteArrayList<Recibida>()
    private val conciliaciones = ConcurrentHashMap<String, Pair<Int, String>>()

    // cada GET que llegó, con su ruta y su consulta, y la cabecera Authorization
    val consultadas = CopyOnWriteArrayList<Pair<String, String?>>()

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

    fun conciliar(
        fecha: LocalDate,
        estado: Int,
        cuerpo: String
    ) {
        conciliaciones[fecha.toString()] = estado to cuerpo
    }

    private fun conciliacion(request: RecordedRequest): MockResponse {
        consultadas += "${request.url.encodedPath}?${request.url.encodedQuery}" to request.headers["Authorization"]
        val (estado, cuerpo) = conciliaciones[request.url.queryParameter("fecha")] ?: (404 to "")
        return MockResponse
            .Builder()
            .code(estado)
            .setHeader("Content-Type", "application/json")
            .body(cuerpo)
            .build()
    }

    fun de(pagoId: String): List<Recibida> = recibidas.filter { it.pagoId == pagoId }

    override fun close() = servidor.close()

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
