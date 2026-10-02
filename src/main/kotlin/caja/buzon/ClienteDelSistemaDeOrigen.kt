package caja.buzon

import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.coroutines.cancellation.CancellationException

// el único camino de caja hacia otro sistema (ClienteHttpDelSistemaDeOrigen y BuzonHttpDelSistemaDeOrigen de caja):
// POST {url}/pagos con el cuerpo del evento TAL CUAL SE CONGELÓ al cobrar, sin recomponerlo ni agregarle nada. la ruta es
// una para los dos tipos de evento: el tipo va dentro y el receptor decide. el token, si hay, va en la cabecera
// Authorization: Bearer, y nunca en el cuerpo. y la única lectura: GET {url}/pagos/conciliacion?fecha= de la
// conciliación del día (leer).
//
// NUNCA DENTRO DE UNA TRANSACCIÓN: lo comprueba antes de llamar, y falla si hay una. una transacción abierta mientras el
// destino tarda retendría su conexión y sus candados el tiempo de la red. la cobranza no lo llama nunca: con el sistema
// de origen apagado, la ventanilla cobra igual.
//
// nunca lanza: lo que pase (sin url, sin conexión, tiempo agotado, una url mal escrita) es una Respuesta, y cuenta como
// un intento. una excepción que saliera de aquí dejaría el evento sin contar su intento, primero en la cola para siempre
@Component
class ClienteDelSistemaDeOrigen(
    private val propiedades: PropiedadesDelBuzon
) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(propiedades.timeout).build()

    suspend fun publicar(
        sistema: String,
        cuerpo: String
    ): Respuesta {
        check(!enTransaccion()) { "El pago a «$sistema» se iba a publicar dentro de una transacción: la llamada al destino va siempre fuera" }
        val destino = propiedades.destinos[sistema]
        val raiz = destino?.url?.trim()?.ifEmpty { null } ?: return sinDireccion(sistema)
        val token = destino.token?.trim()?.ifEmpty { null }
        return try {
            val peticion =
                HttpRequest
                    .newBuilder(URI.create(raiz.trimEnd('/') + RUTA))
                    .timeout(propiedades.timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .apply { if (token != null) header("Authorization", "Bearer $token") }
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                    .build()
            val respuesta = http.sendAsync(peticion, HttpResponse.BodyHandlers.ofString()).await()
            clasificar(sistema, respuesta.statusCode(), respuesta.body(), token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // el tipo y el mensaje, tachados: un mensaje de la librería puede llevar la url, nunca debería llevar el token
            Respuesta.NoContesta(
                tachar("No se pudo publicar el pago: «$sistema» no contesta (${e.javaClass.simpleName}: ${e.message ?: "sin mensaje"}). Se reintenta", token)
            )
        }
    }

    // GET {url}{ruta}: una lectura del sistema de origen (AbonosAplicadosHttp y ClienteHttpDelSistemaDeOrigen.preguntar de
    // caja), la de la conciliación del día. el mismo destino, el mismo token en la cabecera y el mismo timeout que la
    // entrega, y también fuera de toda transacción. nunca lanza y no interpreta: devuelve el código y el cuerpo, tachado,
    // o por qué no contestó, o que no hay a dónde preguntar. quien pregunta decide, y nada de esto es un cero
    suspend fun leer(
        sistema: String,
        ruta: String
    ): Lectura {
        check(!enTransaccion()) { "Se iba a preguntar a «$sistema» dentro de una transacción: la llamada al destino va siempre fuera" }
        val destino = propiedades.destinos[sistema]
        val raiz = destino?.url?.trim()?.ifEmpty { null } ?: return Lectura.SinDireccion
        val token = destino.token?.trim()?.ifEmpty { null }
        return try {
            val peticion =
                HttpRequest
                    .newBuilder(URI.create(raiz.trimEnd('/') + ruta))
                    .timeout(propiedades.timeout)
                    .header("Accept", "application/json")
                    .apply { if (token != null) header("Authorization", "Bearer $token") }
                    .GET()
                    .build()
            val respuesta = http.sendAsync(peticion, HttpResponse.BodyHandlers.ofString()).await()
            Lectura.Contesto(respuesta.statusCode(), tachar(respuesta.body().orEmpty(), token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Lectura.NoContesta(tachar("${e.javaClass.simpleName}: ${e.message ?: "sin mensaje"}", token))
        }
    }

    // la transacción reactiva vive en el contexto de reactor, que la corrutina lleva consigo
    private suspend fun enTransaccion(): Boolean =
        try {
            TransactionSynchronizationManager.forCurrentTransaction().awaitSingle().isActualTransactionActive
        } catch (_: NoTransactionException) {
            false
        }

    private companion object {
        // el buzón de entrada del sistema de origen, bajo su url
        const val RUTA = "/pagos"
    }
}
