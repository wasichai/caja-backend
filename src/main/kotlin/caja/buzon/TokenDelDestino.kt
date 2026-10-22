package caja.buzon

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

// la credencial con la que caja habla a un sistema de origen (caja-backend#50). dos modos, excluyentes (la configuración
// no arranca con los dos): un token fijo (caja.buzon.destinos.<s>.token), que se manda tal cual, o una cuenta de servicio
// del origen (client-id, client-secret y token-url): POST {token-url} con {"clientId","clientSecret"} contesta
// {"token","expiresAt"} (ADR-043 de wasichai, el token dura 15 minutos). se pide a la primera llamada y se reusa hasta
// 60 segundos antes de expiresAt; olvidar() lo suelta (el origen contestó 401 o 403) y la próxima llamada pide uno nuevo.
// sin credencial ninguna, bearer() es null: no se manda cabecera
class TokenDelDestino(
    private val destino: PropiedadesDelBuzon.Destino,
    private val http: WebClient,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val ahora: () -> Instant = Instant::now
) {
    private class Vigente(
        val token: String,
        val vence: Instant
    )

    private val vigente = AtomicReference<Vigente?>()
    private val pidiendo = Mutex()

    // el token a mandar, o null si no hay credencial. lanza FalloDelToken si el origen no da uno: quien llama lo vuelve
    // NoContesta (se reintenta), nunca un rechazo del pago
    suspend fun bearer(): String? {
        val clientId =
            destino.clientId?.trim()?.ifEmpty { null }
                ?: return destino.token?.trim()?.ifEmpty { null }
        enVigor()?.let { return it }
        return pidiendo.withLock { enVigor() ?: pedir(clientId) }
    }

    fun olvidar() = vigente.set(null)

    private fun enVigor(): String? = vigente.get()?.takeIf { ahora().isBefore(it.vence.minus(MARGEN)) }?.token

    private suspend fun pedir(clientId: String): String {
        val url = destino.tokenUrl?.trim()?.ifEmpty { null } ?: throw FalloDelToken("falta token-url para el client-id $clientId")
        val cuerpo = JSON.writeValueAsString(mapOf("clientId" to clientId, "clientSecret" to destino.clientSecret.orEmpty()))
        val (estado, texto) =
            try {
                withTimeout(timeout.toMillis()) {
                    http
                        .post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(cuerpo)
                        .exchangeToMono { r -> r.bodyToMono(String::class.java).defaultIfEmpty("").map { r.statusCode().value() to it } }
                        .awaitSingle()
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) throw FalloDelToken("el client-id $clientId no obtuvo token: sin respuesta a tiempo")
                throw e
            } catch (e: Exception) {
                // solo el tipo: el mensaje de la librería puede llevar la url
                throw FalloDelToken("el client-id $clientId no obtuvo token: no se pudo llamar (${e.javaClass.simpleName})")
            }
        if (estado != 200) throw FalloDelToken("el client-id $clientId no obtuvo token: el origen contestó $estado")
        val json = runCatching { JSON.readTree(texto) }.getOrNull()
        val token =
            json
                ?.get("token")
                ?.takeIf { it.isString }
                ?.asString()
                ?.ifBlank { null }
        val vence = runCatching { Instant.parse(json?.get("expiresAt")?.asString()) }.getOrNull()
        if (token == null || vence == null) throw FalloDelToken("el client-id $clientId no obtuvo token: la respuesta no trae token y expiresAt")
        vigente.set(Vigente(token, vence))
        return token
    }

    class FalloDelToken(
        mensaje: String
    ) : RuntimeException(mensaje)

    private companion object {
        val MARGEN: Duration = Duration.ofSeconds(60)
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
