package caja.buzon

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException

// saca el buzón de salida (PublicadorDelBuzon, EntregarEventos y AnotarLaEntrega de caja; ADR-0026 §3): entrega cada
// PAGO_REGISTRADO y PAGO_ANULADO PENDIENTE al sistema que emitió sus órdenes.
//
// UNA VUELTA: toma el CerrojoBuzon (si otro publicador lo tiene, no hace nada); con él recorre cada organización y lee
// hasta caja.buzon.por-vuelta eventos PENDIENTE, por orden de creación (BuzonStore). cada evento:
//   1. se comprueba contra su recibo (la defensa frente a un pago_evento inventado por la API genérica): si no coincide,
//      NO se envía, y muere con «el evento no coincide con su recibo»;
//   2. se entrega FUERA DE CUALQUIER TRANSACCIÓN (ClienteDelSistemaDeOrigen lo comprueba);
//   3. se marca en SU PROPIA transacción, condicional a que siga como se leyó (BuzonStore): ENTREGADO, o un intento más
//      con su motivo, PENDIENTE o MUERTO (marcaDe).
// cuando muere alguno, la alerta (una línea ERROR que empieza con DINERO COBRADO SIN REGISTRAR) nombra al responsable
// de la conciliación y su canal. un turno con un pago MUERTO no cierra hasta que alguien lo explique por escrito
// (ExplicarPagoSinEntregar).
//
// una organización cuyo buzón revienta no tumba a las demás: se registra y la vuelta sigue. lo ya marcado queda marcado,
// y lo que no se marcó sigue PENDIENTE para la vuelta siguiente; si el destino ya lo tenía, lo recibe otra vez con el
// mismo pagoId y lo deduplica
@Service
class PublicadorDelBuzon(
    private val store: BuzonStore,
    private val cliente: ClienteDelSistemaDeOrigen,
    private val cerrojo: CerrojoBuzon,
    private val propiedades: PropiedadesDelBuzon,
    private val responsable: ResponsableDeLaConciliacion,
    private val reloj: Clock
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // lo que hizo una vuelta, o null si no tomó el cerrojo (otro publicador está dando la suya)
    suspend fun vuelta(): Vuelta? {
        val tomado = cerrojo.tomar() ?: return null
        try {
            var total = Vuelta(0, 0, 0)
            for (buzon in store.buzones()) {
                try {
                    total += deLaOrganizacion(buzon)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("El buzón de la organización {} no se pudo sacar en esta vuelta: queda para la siguiente", buzon.organizacion, e)
                }
            }
            return total
        } finally {
            tomado.soltar()
        }
    }

    private suspend fun deLaOrganizacion(buzon: BuzonStore.Buzon): Vuelta {
        val pendientes = store.pendientes(buzon, propiedades.porVuelta)
        val muertos = mutableListOf<Intento>()
        var entregados = 0
        for (evento in pendientes) {
            val intento = entregarUno(buzon, evento)
            if (!intento.anotado) continue
            when (intento.marca) {
                Marca.ENTREGADO -> entregados++
                Marca.MUERTO -> muertos += intento
                Marca.PENDIENTE -> Unit
            }
        }
        if (pendientes.isNotEmpty()) {
            log.info("Buzón de la organización {}: {} leídos, {} entregados, {} muertos", buzon.organizacion, pendientes.size, entregados, muertos.size)
        }
        if (muertos.isNotEmpty()) alertar(muertos)
        return Vuelta(pendientes.size, entregados, muertos.size)
    }

    // un evento, tal como se leyó: se comprueba, se entrega (si coincide) y se anota. anotado es false si otro publicador
    // ya lo había anotado desde que se leyó: entonces este intento no cuenta
    suspend fun entregarUno(
        buzon: BuzonStore.Buzon,
        evento: EventoDelBuzon
    ): Intento {
        val recibo = store.recibo(buzon, evento.recibo)
        val respuesta =
            incoherencia(evento.tipo, evento.cuerpo, recibo)
                ?.let { Respuesta.Rechazado("$NO_COINCIDE: $it. No se envió: un evento que no escribió la cobranza ni la anulación no sale de la caja") }
                ?: cliente.publicar(evento.sistemaDestino, evento.cuerpo.orEmpty())
        val marca = marcaDe(respuesta, evento.intentos, propiedades.intentos)
        val error =
            when (respuesta) {
                Respuesta.Entregado -> null
                is Respuesta.NoContesta -> recortar(respuesta.motivo)
                is Respuesta.Rechazado -> recortar(respuesta.motivo)
            }
        val anotado =
            if (error == null) {
                store.entregado(buzon, evento, OffsetDateTime.now(reloj))
            } else {
                store.fallido(buzon, evento, error, muere = marca == Marca.MUERTO)
            }
        return Intento(evento, recibo?.numeroImpreso, marca, error, anotado)
    }

    // ADR-0026 §4: un pago que no se pudo entregar es dinero cobrado sin registrar, y avisa a una persona con nombre.
    // una línea ERROR, en una sola línea, que empieza con DINERO COBRADO SIN REGISTRAR (la regla de alertas mira ERROR).
    // ninguno lleva el token: ultimo_error ya va tachado
    private fun alertar(muertos: List<Intento>) {
        log.error(
            "DINERO COBRADO SIN REGISTRAR: {} pago(s) que su sistema de origen no ha podido registrar. Responsable de la conciliación: {}, " +
                "canal {}. Ningún turno de estos recibos cierra hasta que se entreguen o se expliquen uno por uno " +
                "(GET /api/caja/pagos/sin-entregar, POST /api/caja/pagos/{pago_id}/explicacion): {}",
            muertos.size,
            responsable.nombre,
            responsable.canal,
            muertos.joinToString("; ") {
                "pago ${it.evento.eventoId} (${it.evento.tipo}, destino ${it.evento.sistemaDestino}, recibo ${it.numero ?: it.evento.recibo}, " +
                    "turno ${it.evento.turno}, ${it.evento.intentos + 1} intento(s)): ${it.error?.replace(Regex("\\s+"), " ")}"
            }
        )
    }

    data class Vuelta(
        val leidos: Int,
        val entregados: Int,
        val muertos: Int
    ) {
        operator fun plus(otra: Vuelta) = Vuelta(leidos + otra.leidos, entregados + otra.entregados, muertos + otra.muertos)
    }

    class Intento(
        val evento: EventoDelBuzon,
        val numero: String?,
        val marca: Marca,
        val error: String?,
        val anotado: Boolean
    )
}
