package caja.buzon

import caja.comun.ANULACION_RECIBO
import caja.comun.Candado
import caja.comun.Candados
import caja.comun.EscrituraDeCaja
import caja.comun.LINEA_RECIBO
import caja.comun.ORDEN_DE_COBRO
import caja.comun.PAGO_EVENTO
import caja.comun.RECIBO
import caja.comun.Records
import caja.modelo.AnulacionRecibo
import caja.modelo.EVENTO_ENTREGADO
import caja.modelo.EVENTO_MUERTO
import caja.modelo.EVENTO_PENDIENTE
import caja.modelo.LineaRecibo
import caja.modelo.OrdenDeCobro
import caja.modelo.PagoEvento
import caja.modelo.Recibo
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.platform.ClusterLock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.min

// lo que el publicador lee y marca en la base, y NADA MÁS. el trabajo de fondo no tiene usuario: lee y escribe por
// RecordService COMO LA PLATAFORMA de cada organización (asPlatform de wasichai), que lo ve todo, sin roles, como un
// ADMIN. cada marca es un update de core: core escribe su auditoría, con usuario null y una razón fija, y avisa a los
// RecordChangeListener. lleva la marca EscrituraDeCaja: sin ella GuardiaDeEscrituras no deja escribir pago_evento, ni a
// la plataforma
//
// CADA MARCA VA EN SU PROPIA TRANSACCIÓN, BAJO EL CANDADO DE SU EVENTO, Y SOLO SI SIGUE COMO SE LEYÓ. core no tiene un
// update condicional: bajo Candado.PAGO del evento (el mismo que toma ExplicarPagoSinEntregar) se relee, y se escribe
// solo si sigue PENDIENTE con los intentos leídos. dos publicadores que leyeron el mismo evento cuentan un solo intento,
// y una marca nunca pisa un evento que ya cambió (uno EXPLICADO, uno que otro entregó). la llamada al destino ocurre
// entre una lectura y una marca, sin ninguna transacción abierta ni candado tomado (PublicadorDelBuzon)
@Component
class BuzonStore(
    private val records: RecordService,
    private val objetos: CustomObjectRepository,
    private val cerrojos: ClusterLock
) {
    // el buzón de cada organización que tiene pago_evento (findAllOrganizations es de la plataforma, sin usuario). una a
    // la que le falta el resto del modelo de caja ya no se salta en silencio: lo que no puede leer falla en cada vuelta,
    // la lista de pendientes con un ERROR de su organización y el recibo de un evento como un intento (PublicadorDelBuzon)
    suspend fun buzones(): List<Buzon> =
        objetos
            .findAllOrganizations()
            .filter { it.name == PAGO_EVENTO }
            .map { it.organizationId }
            .distinct()
            .map(::Buzon)

    // hasta cuantos eventos PENDIENTE, por orden de creación (el del cobro)
    suspend fun pendientes(
        buzon: Buzon,
        cuantos: Int
    ): List<EventoDelBuzon> =
        records.asPlatform(buzon.organizacion) {
            leer(PAGO_EVENTO, mapOf("estado" to EVENTO_PENDIENTE), cuantos).map { fila ->
                val evento = Records.read<PagoEvento>(fila.id, fila.attributes)
                EventoDelBuzon(
                    id = UUID.fromString(fila.id),
                    creadoEn = sello(fila.createdAt),
                    eventoId = evento.eventoId!!,
                    tipo = evento.tipo,
                    sistemaDestino = evento.sistemaDestino!!,
                    recibo = evento.recibo,
                    turno = evento.turno,
                    cuerpo = evento.cuerpo,
                    intentos = evento.intentos ?: 0L
                )
            }
        }

    // el recibo del evento, tal como está, con lo que hace falta para volver a componer el cuerpo de su evento
    // (incoherencia): el recibo, sus líneas, el actualizado_a de cada orden, su anulación y los eventos de su buzón por
    // (created_at, id), cada fila con su sello (su created_at, o el updated_at de la orden). cada evento lleva además su
    // estado: un PAGO_ANULADO no sale antes que su PAGO_REGISTRADO (salida). null si no existe
    suspend fun recibo(
        buzon: Buzon,
        reciboId: String?
    ): ReciboDelEvento? {
        val id = reciboId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        return records.asPlatform(buzon.organizacion) {
            val fila =
                records.list(RECIBO, RecordQuery(PageRequest.of(0, 1), ids = listOf(id), count = false)).content.singleOrNull()
                    ?: return@asPlatform null
            val delRecibo = mapOf("recibo" to id.toString())
            val lineas =
                leer(LINEA_RECIBO, delRecibo).map { l ->
                    val linea = Records.read<LineaRecibo>(l.id, l.attributes)
                    LineaDelEvento(linea.orden, linea.sistemaOrigen, linea.referenciaExterna, linea.monto, sello(l.createdAt))
                }
            val ordenes =
                lineas
                    .mapNotNull { it.orden }
                    .distinct()
                    .map(UUID::fromString)
                    .chunked(PageRequest.MAX_SIZE)
                    .flatMap { ids -> records.list(ORDEN_DE_COBRO, RecordQuery(PageRequest.of(0, ids.size), ids = ids, count = false)).content }
                    .associate { o -> o.id to OrdenDelEvento(Records.read<OrdenDeCobro>(o.id, o.attributes).actualizadoA, sello(o.updatedAt)) }
            val anulacion =
                leer(ANULACION_RECIBO, delRecibo, 1).firstOrNull()?.let { a ->
                    val acta = Records.read<AnulacionRecibo>(a.id, a.attributes)
                    AnulacionDelEvento(acta.motivo, acta.fecha, sello(a.createdAt))
                }
            val eventos =
                leer(PAGO_EVENTO, delRecibo).map { e ->
                    val evento = Records.read<PagoEvento>(e.id, e.attributes)
                    EventoDelRecibo(UUID.fromString(e.id), evento.eventoId!!, evento.tipo, sello(e.createdAt), evento.estado)
                }
            ReciboDelEvento(Records.read<Recibo>(fila.id, fila.attributes), sello(fila.createdAt), lineas, ordenes, anulacion, eventos)
        }
    }

    // el evento llegó: ENTREGADO, con su hora y su intento. false si ya no estaba como se leyó (otro lo marcó)
    suspend fun entregado(
        buzon: Buzon,
        evento: EventoDelBuzon,
        cuando: OffsetDateTime
    ): Boolean =
        marcar(
            buzon,
            evento,
            mapOf("estado" to EVENTO_ENTREGADO, "intentos" to evento.intentos + 1, "entregado_en" to cuando.toString(), "ultimo_error" to null),
            RAZON_ENTREGADO
        )

    // el evento no llegó: cuenta su intento con su motivo, y queda PENDIENTE o MUERTO. false si ya no estaba como se leyó
    suspend fun fallido(
        buzon: Buzon,
        evento: EventoDelBuzon,
        error: String,
        muere: Boolean
    ): Boolean =
        marcar(
            buzon,
            evento,
            mapOf("estado" to if (muere) EVENTO_MUERTO else EVENTO_PENDIENTE, "intentos" to evento.intentos + 1, "ultimo_error" to error),
            if (muere) RAZON_MUERTO else RAZON_FALLIDO
        )

    // una marca, en su propia transacción y bajo el candado de su evento: lo relee y, si sigue PENDIENTE con los intentos
    // leídos, escribe lo guardado con los cambios encima (el update de core reemplaza cada campo, y vuelve a validarlos
    // todos: una fila con un valor que su tipo no admite, escrita en la base por fuera de caja tras quitar el CHECK de su
    // columna, no se puede marcar, y lanza la WasichaiException de la plataforma; PublicadorDelBuzon.intentar). el
    // candado se suelta con el commit
    private suspend fun marcar(
        buzon: Buzon,
        evento: EventoDelBuzon,
        cambios: Map<String, Any?>,
        razon: String
    ): Boolean =
        records.asPlatform(buzon.organizacion) {
            cerrojos.withXactLock(Candados.clave(Candado.PAGO, evento.eventoId)) {
                val actual =
                    try {
                        records.get(PAGO_EVENTO, evento.id)
                    } catch (_: NotFoundException) {
                        return@withXactLock false
                    }
                val intentos = (actual.attributes["intentos"] as Number?)?.toLong() ?: 0L
                if (actual.attributes["estado"] != EVENTO_PENDIENTE || intentos != evento.intentos) return@withXactLock false
                withContext(EscrituraDeCaja) { records.update(PAGO_EVENTO, evento.id, RecordRequest(actual.attributes + cambios), razon) }
                true
            }
        }

    // los registros de un objeto que cumplen los filtros, por (created_at, id), hasta `hasta`: de 200 en 200 (el máximo
    // de core) y sin contarlos. cada página sigue a la anterior por su cursor (after): ninguno sale dos veces ni se salta
    private suspend fun leer(
        objeto: String,
        filtros: Map<String, String>,
        hasta: Int = Int.MAX_VALUE
    ): List<RecordResponse> {
        val filas = mutableListOf<RecordResponse>()
        var despues: String? = null
        while (filas.size < hasta) {
            val pagina =
                records.list(
                    objeto,
                    RecordQuery(
                        page = PageRequest.of(0, min(hasta - filas.size, PageRequest.MAX_SIZE)),
                        sort = POR_CREACION,
                        filters = filtros,
                        count = false,
                        after = despues
                    )
                )
            filas += pagina.content
            despues = pagina.nextCursor ?: break
        }
        return filas
    }

    // el sello de una fila: el instante que postgres le dio con now(), el comienzo de su transacción (incoherencia), en
    // microsegundos, como lo guarda
    private fun sello(instante: Instant?): Instant = checkNotNull(instante) { "una fila sin su created_at/updated_at" }

    class Buzon(
        val organizacion: UUID
    )

    companion object {
        private const val POR_CREACION = "created_at"

        // la razón de cada marca en su auditoría: un texto fijo. nunca el error ni lo que contestó el destino (eso va en
        // ultimo_error): la plataforma rechaza una razón con caracteres de control
        private const val RAZON_ENTREGADO = "entrega del buzón: ENTREGADO"
        private const val RAZON_FALLIDO = "entrega del buzón: intento fallido"
        private const val RAZON_MUERTO = "entrega del buzón: MUERTO"
    }
}

// un evento PENDIENTE como lo lee el publicador. intentos es el valor leído: la marca solo cuenta si sigue valiendo eso
data class EventoDelBuzon(
    val id: UUID,
    val creadoEn: Instant,
    val eventoId: String,
    val tipo: String?,
    val sistemaDestino: String,
    val recibo: String?,
    val turno: String?,
    val cuerpo: String?,
    val intentos: Long
)
