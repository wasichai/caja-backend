package caja.buzon

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import kotlin.coroutines.cancellation.CancellationException

// el bucle de fondo del publicador. wasichai no programa nada (wasichai#18): es un SmartLifecycle propio, como
// AutomationDrain de wasichai, con su alcance de corrutinas, como la emisión masiva de srtm. SOLO ARRANCA SI
// caja.buzon.habilitado: apagado (el valor por defecto) no hay bucle y los pagos se quedan PENDIENTE. espera el
// intervalo, da una vuelta y vuelve a esperar: el intervalo cuenta desde el final de la vuelta, así que una vuelta lenta
// no se solapa con la siguiente. una vuelta que revienta se registra y el bucle sigue. al parar la app se cancela, y la
// vuelta en curso suelta el cerrojo (NonCancellable)
@Component
class BucleDelBuzon(
    private val publicador: PublicadorDelBuzon,
    private val propiedades: PropiedadesDelBuzon
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("buzon"))

    @Volatile
    private var trabajo: Job? = null

    override fun start() {
        if (!propiedades.habilitado || trabajo != null) return
        log.info(
            "El buzón de salida está habilitado: una vuelta cada {}, hasta {} eventos por organización, {} intentos. Destinos: {}",
            propiedades.intervalo,
            propiedades.porVuelta,
            propiedades.intentos,
            propiedades.destinos.map { (sistema, destino) -> "$sistema → ${destino.url ?: "(sin url)"}" }.ifEmpty { listOf("ninguno") }
        )
        trabajo =
            alcance.launch {
                while (isActive) {
                    delay(propiedades.intervalo.toMillis())
                    try {
                        publicador.vuelta()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("Una vuelta del buzón de salida falló; el bucle sigue", e)
                    }
                }
            }
    }

    override fun stop() {
        val enCurso = trabajo ?: return
        trabajo = null
        runBlocking { withTimeoutOrNull(ESPERA_AL_PARAR) { enCurso.cancelAndJoin() } }
    }

    override fun isRunning(): Boolean = trabajo?.isActive == true

    @PreDestroy
    fun cerrar() = alcance.cancel()

    private companion object {
        // lo que espera la vuelta en curso al parar: que suelte el cerrojo y su conexión antes de que se cierre el pool
        const val ESPERA_AL_PARAR = 15_000L
    }
}
