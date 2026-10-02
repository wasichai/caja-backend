package caja.buzon

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// el buzón de entrada de un sistema de origen, por http de verdad (MockWebServer): contesta a cada pago lo que la
// prueba le diga por su pagoId (503 si no le dijo nada) y guarda lo que recibió. alRecibir corre mientras el
// publicador espera la respuesta: ahí se mira la base desde otra conexión
class SistemaDeOrigenFalso : AutoCloseable {
    private val servidor = MockWebServer()
    private val respuestas = ConcurrentHashMap<String, Pair<Int, String>>()
    val recibidas = CopyOnWriteArrayList<Recibida>()

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

    fun de(pagoId: String): List<Recibida> = recibidas.filter { it.pagoId == pagoId }

    override fun close() = servidor.close()

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
