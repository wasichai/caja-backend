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
        var entregados = 0
        var muertos = 0
        for (evento in pendientes) {
            val intento = intentar(buzon, evento)
            if (!intento.anotado) continue
            when (intento.marca) {
                Marca.ENTREGADO -> entregados++
                // la alerta sale en cuanto el pago muere, no al final de la vuelta: si algo corta la vuelta después, el
                // pago ya está MUERTO y nadie más lo va a anunciar
                Marca.MUERTO -> {
                    muertos++
                    alertar(intento)
                }
                Marca.PENDIENTE -> Unit
            }
        }
        if (pendientes.isNotEmpty()) {
            log.info("Buzón de la organización {}: {} leídos, {} entregados, {} muertos", buzon.organizacion, pendientes.size, entregados, muertos)
        }
        return Vuelta(pendientes.size, entregados, muertos)
    }

    // un evento, y lo que no se esperaba (EntregarEventos de caja, #109): un fallo que no es del destino (la base al
    // leer su recibo o al marcarlo) CUENTA COMO UN INTENTO, con su tipo en ultimo_error (el mensaje va al registro, con su
    // traza), y la vuelta sigue con el siguiente. si no contara, el evento, primero en la cola, se reintentaría en cada
    // vuelta sin morir ni avisar, y atascaría el buzón de su organización. lo que no se atrapa es un fallo al anotar ESE
    // intento: entonces no hay dónde contarlo, y la vuelta de la organización se corta (lo ya anotado queda anotado)
    private suspend fun intentar(
        buzon: BuzonStore.Buzon,
        evento: EventoDelBuzon
    ): Intento =
        try {
            entregarUno(buzon, evento)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("El evento {} no se pudo entregar por un fallo inesperado; cuenta como intento", evento.eventoId, e)
            val error = recortar("Fallo inesperado al entregar: ${e.javaClass.simpleName}. Se reintenta: el detalle está en el registro")
            val marca = marcaDe(Respuesta.NoContesta(error), evento.intentos, propiedades.intentos)
            val anotado = store.fallido(buzon, evento, error, muere = marca == Marca.MUERTO)
            Intento(evento, null, marca, error, anotado)
        }

    // un evento, tal como se leyó: se comprueba, se entrega (si coincide) y se anota. anotado es false si otro publicador
    // ya lo había anotado desde que se leyó: entonces este intento no cuenta
    suspend fun entregarUno(
        buzon: BuzonStore.Buzon,
        evento: EventoDelBuzon
    ): Intento {
        val recibo = store.recibo(buzon, evento.recibo)
        val respuesta =
            incoherencia(evento, recibo)
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
        return Intento(evento, recibo?.recibo?.numeroImpreso, marca, error, anotado)
    }

    // ADR-0026 §4: un pago que no se pudo entregar es dinero cobrado sin registrar, y avisa a una persona con nombre.
    // UNA línea ERROR que empieza con DINERO COBRADO SIN REGISTRAR (la regla de alertas mira ERROR). cada valor va en una
    // sola línea (enUnaLinea): un sistema_destino escrito por la API genérica con un salto de línea no puede inyectar
    // otra línea en el registro. ninguno lleva el token: ultimo_error ya va tachado
    private fun alertar(muerto: Intento) {
        val e = muerto.evento
        log.error(
            "DINERO COBRADO SIN REGISTRAR: el pago {} ({}, destino {}, recibo {}, turno {}, {} intento(s)) no lo pudo registrar su " +
                "sistema de origen: {}. Responsable de la conciliación: {}, canal {}. Su turno no cierra hasta que se entregue o se " +
                "explique por escrito (GET /api/caja/pagos/sin-entregar, POST /api/caja/pagos/{pago_id}/explicacion)",
            enUnaLinea(e.eventoId),
            enUnaLinea(e.tipo),
            enUnaLinea(e.sistemaDestino),
            enUnaLinea(muerto.numero ?: e.recibo),
            enUnaLinea(e.turno),
            e.intentos + 1,
            enUnaLinea(muerto.error),
            enUnaLinea(responsable.nombre),
            enUnaLinea(responsable.canal)
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
