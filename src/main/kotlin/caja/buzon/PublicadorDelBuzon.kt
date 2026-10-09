package caja.buzon

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import wasichai.core.common.WasichaiException
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException

// saca el buzón de salida (PublicadorDelBuzon, EntregarEventos y AnotarLaEntrega de caja; ADR-0026 §3): entrega cada
// PAGO_REGISTRADO y PAGO_ANULADO PENDIENTE al sistema que emitió sus órdenes.
//
// UNA VUELTA: toma el CerrojoBuzon (si otro publicador lo tiene, no hace nada); con él recorre cada organización y lee
// hasta caja.buzon.por-vuelta eventos PENDIENTE, por orden de creación (BuzonStore). cada evento:
//   1. se comprueba contra su recibo (la defensa frente a un pago_evento inventado por fuera de caja): si no coincide,
//      NO se envía, y muere con «el evento no coincide con su recibo»;
//   2. un PAGO_ANULADO no sale antes que su PAGO_REGISTRADO (salida, caja-backend#23): mientras ése siga PENDIENTE,
//      espera sin intento; si nunca llegó (MUERTO, EXPLICADO), no se envía y muere con su motivo;
//   3. se entrega FUERA DE CUALQUIER TRANSACCIÓN (ClienteDelSistemaDeOrigen lo comprueba);
//   4. se marca en SU PROPIA transacción, bajo el candado de su evento y solo si sigue como se leyó (BuzonStore):
//      ENTREGADO, o un intento más con su motivo, PENDIENTE o MUERTO (marcaDe).
// cuando muere alguno, la alerta (una línea ERROR que empieza con DINERO COBRADO SIN REGISTRAR) nombra al responsable
// de la conciliación y su canal. un turno con un pago MUERTO no cierra hasta que alguien lo explique por escrito
// (ExplicarPagoSinEntregar).
//
// una organización cuyo buzón revienta no tumba a las demás: se registra y la vuelta sigue. lo ya marcado queda marcado,
// y lo que no se marcó sigue PENDIENTE para la vuelta siguiente; si el destino ya lo tenía, lo recibe otra vez con el
// mismo pagoId y lo deduplica. un evento cuya fila la plataforma no deja marcar no atasca a los de su organización: deja
// su ERROR y la vuelta sigue (intentar)
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
        // use lo suelta al terminar, también si la vuelta revienta o se cancela
        return tomado.use {
            var total = Vuelta(0, 0, 0, 0)
            for (buzon in store.buzones()) {
                try {
                    total += deLaOrganizacion(buzon)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("El buzón de la organización {} no se pudo sacar en esta vuelta: queda para la siguiente", buzon.organizacion, e)
                }
            }
            total
        }
    }

    private suspend fun deLaOrganizacion(buzon: BuzonStore.Buzon): Vuelta {
        val pendientes = store.pendientes(buzon, propiedades.porVuelta)
        var entregados = 0
        var muertos = 0
        var enEspera = 0
        for (evento in pendientes) {
            val intento = intentar(buzon, evento)
            if (intento == null) {
                enEspera++
                continue
            }
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
            log.info(
                "Buzón de la organización {}: {} leídos, {} entregados, {} muertos, {} esperan a su pago",
                buzon.organizacion,
                pendientes.size,
                entregados,
                muertos,
                enEspera
            )
        }
        return Vuelta(pendientes.size, entregados, muertos, enEspera)
    }

    // un evento, y lo que no se esperaba (EntregarEventos de caja, #109): un fallo que no es del destino (la base al
    // leer su recibo o al marcarlo) CUENTA COMO UN INTENTO, con su tipo en ultimo_error (el mensaje va al registro, con su
    // traza), y la vuelta sigue con el siguiente. si no contara, el evento, primero en la cola, se reintentaría en cada
    // vuelta sin morir ni avisar, y atascaría el buzón de su organización. si tampoco se puede anotar ESE intento:
    // - porque la plataforma rechaza la fila misma (una WasichaiException: un valor que su tipo no admite, escrito en la
    //   base por fuera de caja tras quitar el CHECK de su columna; el update de core reescribe la fila entera y vuelve a
    //   validar cada campo), ese evento no se puede marcar nunca. es un problema de ese evento, no del buzón: sigue
    //   PENDIENTE sin contar el intento, deja en cada vuelta una línea ERROR que lo nombra (filaRechazada) hasta que
    //   alguien corrija la fila en la base, y la vuelta sigue con el siguiente. si llegó a salir, sale otra vez con el
    //   mismo pagoId, y el destino lo deduplica;
    // - por otro fallo (la base caída), no hay dónde contarlo, y la vuelta de la organización se corta (lo ya anotado
    //   queda anotado).
    // null: el evento espera a su pago, y no hubo intento
    private suspend fun intentar(
        buzon: BuzonStore.Buzon,
        evento: EventoDelBuzon
    ): Intento? =
        try {
            entregarUno(buzon, evento)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("El evento {} no se pudo entregar por un fallo inesperado; cuenta como intento", evento.eventoId, e)
            val error = recortar("Fallo inesperado al entregar: ${e.javaClass.simpleName}. Se reintenta: el detalle está en el registro")
            val marca = marcaDe(Respuesta.NoContesta(error), evento.intentos, propiedades.intentos)
            val anotado =
                try {
                    store.fallido(buzon, evento, error, muere = marca == Marca.MUERTO)
                } catch (rechazo: WasichaiException) {
                    filaRechazada(buzon, evento, rechazo)
                    false
                }
            Intento(evento, null, marca, error, anotado)
        }

    // UNA línea ERROR por un evento cuya fila la plataforma no deja marcar. cada valor en una sola línea (enUnaLinea),
    // como la alerta: el tipo y el destino son lo que haya en la base. sin la traza: la lleva el WARN del fallo inesperado
    private fun filaRechazada(
        buzon: BuzonStore.Buzon,
        evento: EventoDelBuzon,
        rechazo: WasichaiException
    ) {
        log.error(
            "El evento {} del buzón ({}, destino {}, recibo {}) de la organización {} no se puede anotar: la plataforma rechaza su fila " +
                "({}). Sigue PENDIENTE, sin contar el intento, y vuelve en cada vuelta hasta que alguien corrija la fila en la base; la " +
                "vuelta sigue con el siguiente",
            enUnaLinea(evento.eventoId),
            enUnaLinea(evento.tipo),
            enUnaLinea(evento.sistemaDestino),
            enUnaLinea(evento.recibo),
            buzon.organizacion,
            enUnaLinea(rechazo.message + rechazo.violations.joinToString("") { "; ${it.field} ${it.message}" })
        )
    }

    // un evento, tal como se leyó: se comprueba, se entrega (si coincide y le toca salir) y se anota. anotado es false si
    // otro publicador ya lo había anotado desde que se leyó (o, en intentar, si la plataforma rechaza su fila): entonces
    // este intento no cuenta. null si es un PAGO_ANULADO
    // cuyo PAGO_REGISTRADO sigue PENDIENTE: no se llamó ni se anotó nada, y no cuenta intento (salida)
    suspend fun entregarUno(
        buzon: BuzonStore.Buzon,
        evento: EventoDelBuzon
    ): Intento? {
        val recibo = store.recibo(buzon, evento.recibo)
        val respuesta =
            incoherencia(evento, recibo)
                ?.let { Respuesta.Rechazado("$NO_COINCIDE: $it. No se envió: un evento que no escribió la cobranza ni la anulación no sale de la caja") }
                ?: when (val decision = salida(evento, recibo!!)) {
                    Salida.Sale -> cliente.publicar(evento.sistemaDestino, evento.cuerpo.orEmpty())
                    is Salida.NoSale -> Respuesta.Rechazado(decision.motivo)
                    is Salida.Espera -> {
                        log.debug("El evento {} no sale todavía: {}", evento.eventoId, decision.motivo)
                        return null
                    }
                }
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
    // sola línea (enUnaLinea): un sistema_destino escrito en la base con un salto de línea no puede inyectar
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

    // enEspera: los PAGO_ANULADO que no salieron porque su PAGO_REGISTRADO sigue PENDIENTE (sin intento)
    data class Vuelta(
        val leidos: Int,
        val entregados: Int,
        val muertos: Int,
        val enEspera: Int
    ) {
        operator fun plus(otra: Vuelta) = Vuelta(leidos + otra.leidos, entregados + otra.entregados, muertos + otra.muertos, enEspera + otra.enEspera)
    }

    class Intento(
        val evento: EventoDelBuzon,
        val numero: String?,
        val marca: Marca,
        val error: String?,
        val anotado: Boolean
    )
}
