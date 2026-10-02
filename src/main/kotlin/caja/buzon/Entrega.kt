package caja.buzon

import caja.cobro.LineaRecibo
import caja.cobro.NORMAL
import caja.cobro.OrdenDeCobro
import caja.cobro.PAGO_REGISTRADO
import caja.cobro.Recibo
import caja.cobro.cuerpoPagoRegistrado
import caja.recibo.PAGO_ANULADO
import caja.recibo.cuerpoPagoAnulado
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate
import java.util.UUID

// las reglas puras de la entrega de un evento del buzón (ClienteHttpDelSistemaDeOrigen, EntregarEventos y
// RespuestaAjena de caja): qué es cada respuesta del destino, qué cabe en ultimo_error, qué marca deja cada intento y
// cuándo un evento no coincide con su recibo. sin Spring, sin red y sin base: el publicador las aplica, las pruebas
// las fijan

// el ancho del diagnóstico que se guarda en pago_evento.ultimo_error (varchar(400) en caja). lo lee quien explica el
// pago, y no hay otro sitio donde acabe: el remedio va delante y lo que se corta es la cola
const val LARGO_ULTIMO_ERROR = 400

// lo que guarda un evento que el publicador no envía porque no cuadra con su recibo (la defensa frente a un pago_evento
// inventado por la API genérica, wasichai#15)
const val NO_COINCIDE = "el evento no coincide con su recibo"

// cuánto del cuerpo de una respuesta entra en el diagnóstico: un problem+json entero sí, la página de error de un proxy no
private const val LARGO_DEL_CUERPO = 200

private val JSON: JsonMapper = JsonMapper.builder().build()

// lo que dijo el destino, en los tres sentidos que importan (BuzonDelSistemaDeOrigen de caja). NO CONTESTA se arregla
// del lado del despliegue y se reintenta; RECHAZADO no va a cambiar solo y no se reintenta: confundirlos gasta los
// intentos de un rechazo, o mata por una credencial un pago que se habría entregado
sealed interface Respuesta {
    data object Entregado : Respuesta

    data class NoContesta(
        val motivo: String
    ) : Respuesta

    data class Rechazado(
        val motivo: String
    ) : Respuesta
}

// qué queda en la fila después de un intento
enum class Marca { ENTREGADO, PENDIENTE, MUERTO }

// el recibo de un evento tal como está guardado, con todo lo que hace falta para volver a componer el cuerpo de su
// evento con los mismos compositores que la cobranza y la anulación: el recibo, sus líneas (congeladas), la fecha a la
// que estaba cada orden, su anulación y los eventos de su buzón por orden de creación (created_at, id)
data class ReciboDelEvento(
    val recibo: Recibo,
    val lineas: List<LineaRecibo>,
    val actualizadoDeLasOrdenes: Map<String, LocalDate>,
    val anulacion: AnulacionDelEvento?,
    val eventos: List<EventoDelRecibo>
)

data class AnulacionDelEvento(
    val motivo: String?,
    val fecha: LocalDate?
)

data class EventoDelRecibo(
    val id: UUID,
    val eventoId: String,
    val tipo: String?
)

// la respuesta del destino a POST {url}/pagos (ClienteHttpDelSistemaDeOrigen.publicar de caja). el 409 es «ya lo
// tengo»: el receptor deduplicó por pagoId, y es un éxito. 401 y 403 hablan de quien llama, no del pago: se reintentan,
// y cada uno dice dónde se arregla. el resto de los 4xx es un rechazo del pago. un 5xx, o cualquier otra cosa, no
// contesta. la clasificación mira el código, nunca el cuerpo: el cuerpo lo escribe el otro sistema
fun clasificar(
    sistema: String,
    estado: Int,
    cuerpo: String?,
    token: String?
): Respuesta {
    val contesto = contestado(cuerpo, token)
    return when {
        estado == 200 || estado == 201 || estado == 202 || estado == 409 -> Respuesta.Entregado
        estado == 401 || estado == 403 -> Respuesta.NoContesta(deCredencial(sistema, estado, token, contesto))
        estado in 400..499 ->
            Respuesta.Rechazado("«$sistema» rechazó el pago con $estado. Esto NO se reintenta: el motivo no va a cambiar solo. Contestó $contesto")
        else -> Respuesta.NoContesta("«$sistema» contestó $estado al publicar el pago: se reintenta. Contestó $contesto")
    }
}

// no hay a dónde llamar: no es que no conteste, es que no se sabe dónde está. se reintenta, porque se arregla poniendo
// la línea, y entonces los pagos encolados salen solos
fun sinDireccion(sistema: String): Respuesta.NoContesta =
    Respuesta.NoContesta(
        "No hay dirección configurada para el sistema «$sistema»: falta caja.buzon.destinos.$sistema.url. Se reintenta: al " +
            "configurarla, los pagos encolados salen solos"
    )

// el diagnóstico de un 401 o un 403: tres ramas, porque se arreglan en tres sitios
private fun deCredencial(
    sistema: String,
    estado: Int,
    token: String?,
    contesto: String
): String {
    val porque =
        when {
            estado == 403 ->
                "la credencial SÍ vale (el destino la validó) y lo que falta es un permiso de esta caja sobre su buzón de pagos: se " +
                    "concede en el destino"
            token.isNullOrBlank() -> "esta caja no manda ninguna credencial: falta caja.buzon.destinos.$sistema.token"
            else -> "la credencial que manda esta caja no vale o caducó (caja.buzon.destinos.$sistema.token)"
        }
    return "«$sistema» contestó $estado al publicar el pago: $porque. NO es un rechazo del pago: se reintenta. Contestó $contesto"
}

// lo que mandó el destino, en una línea, sin credenciales y recortado: es lo único que dice por qué
private fun contestado(
    cuerpo: String?,
    token: String?
): String {
    val limpio = tachar(cuerpo.orEmpty(), token).replace(Regex("\\s+"), " ").trim()
    if (limpio.isEmpty()) return "con el cuerpo vacío"
    val corto = if (limpio.length <= LARGO_DEL_CUERPO) limpio else limpio.take(LARGO_DEL_CUERPO) + "…"
    return "«$corto»"
}

// lo que tiene forma de credencial, tachado (RespuestaAjena.limpiar de caja). un proxy puede devolver el eco de la
// petición con su Authorization dentro, y esto acaba en una columna que lee un cajero y en el registro. el token
// configurado se tacha además tal cual, esté donde esté
fun tachar(
    texto: String,
    token: String?
): String {
    var limpio = if (token.isNullOrBlank()) texto else texto.replace(token, TACHADO)
    limpio = AUTORIZACION.replace(limpio) { "${it.groupValues[1]}$TACHADO" }
    limpio = PORTADOR.replace(limpio) { "${it.groupValues[1]} $TACHADO" }
    limpio = CON_VALOR.replace(limpio) { "${it.groupValues[1]}$TACHADO" }
    return limpio
}

private const val TACHADO = "«…»"
private val AUTORIZACION = Regex("(?i)(\"?authorization\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\r\\n}]+)")
private val PORTADOR = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+")
private val CON_VALOR =
    Regex(
        "(?i)(\"?(?:client[_-]?secret|secret|access[_-]?token|refresh[_-]?token|id[_-]?token|token|password|passwd|clave|credencial)\"?" +
            "\\s*[:=]\\s*)(\"[^\"]*\"|[^\\s,;&}\\]]+)"
    )

// lo que cabe en ultimo_error. el corte se ve («…») en vez de hacerse en silencio
fun recortar(texto: String): String = if (texto.length <= LARGO_ULTIMO_ERROR) texto else texto.take(LARGO_ULTIMO_ERROR - 1) + "…"

// qué queda en la fila tras un intento que se leyó con intentosLeidos: entregado cuenta el intento; un rechazo muere
// ya; lo que no contesta sigue PENDIENTE hasta que intentos + 1 llega al máximo, y entonces muere
fun marcaDe(
    respuesta: Respuesta,
    intentosLeidos: Long,
    maximos: Int
): Marca =
    when (respuesta) {
        Respuesta.Entregado -> Marca.ENTREGADO
        is Respuesta.Rechazado -> Marca.MUERTO
        is Respuesta.NoContesta -> if (intentosLeidos + 1 >= maximos) Marca.MUERTO else Marca.PENDIENTE
    }

// por qué un evento no coincide con su recibo, o null si coincide. es la defensa (a) frente a la segunda puerta (la API
// genérica de wasichai, wasichai#15): un pago_evento que escribió alguien por POST o editó por PUT
// /api/objects/pago_evento/records no sale de la caja.
//
// LA COMPROBACIÓN MÁS FUERTE QUE SE PUEDE HACER SIN FIRMAS: el cuerpo esperado se VUELVE A COMPONER desde lo guardado, con
// los MISMOS compositores que usan la cobranza (cuerpoPagoRegistrado) y la anulación (cuerpoPagoAnulado), y el cuerpo
// del evento tiene que ser igual a él, campo a campo. así coinciden, sin enumerarlos, el pagoId (el evento_id de la fila),
// el sistema, el recibo, el pagador, el total y cada orden con su referencia, su importe y su fecha; y en una anulación,
// el motivo, la fecha y el pagoOriginalId (el evento_id del PAGO_REGISTRADO de ese recibo). además:
// - el evento tiene que ser el PRIMERO de su tipo en su recibo, por (created_at, id): la cobranza y la anulación
//   escriben uno solo, en la misma transacción que el recibo o su acta, así que otro posterior es una copia (la copia
//   exacta de un evento legítimo con otro pagoId, que el destino tomaría por otro pago);
// - el sistema_destino de la fila es el de las líneas del recibo.
// de dónde sale cada cosa: el recibo y sus líneas están congelados (ningún rol los edita); de la orden solo se toma su
// actualizado_a, que no está en la línea; la referencia, el importe y el sistema de cada orden salen de su línea, que
// es lo que se cobró. lo ÚNICO que no se compara tal cual es el ORDEN de las órdenes en el cuerpo: la cobranza las
// escribe en el orden de la petición, que no se guarda (las líneas nacen con el mismo created_at), así que se comparan
// ordenadas por ordenId
fun incoherencia(
    evento: EventoDelBuzon,
    recibo: ReciboDelEvento?
): String? {
    if (recibo == null) return "su recibo no existe"
    val tipo = evento.tipo
    val numero = recibo.recibo.numeroImpreso
    val primero = recibo.eventos.firstOrNull { it.tipo == tipo }
    if (primero?.id != evento.id) {
        return "es una copia: el recibo $numero ya tiene su ${enUnaLinea(tipo)}, el ${primero?.eventoId}"
    }
    val sistemas = recibo.lineas.map { it.sistemaOrigen }.distinct()
    if (sistemas != listOf(evento.sistemaDestino)) {
        return "el sistema de destino (${enUnaLinea(evento.sistemaDestino)}) no es el de las órdenes del recibo ($sistemas)"
    }
    val esperado =
        when (tipo) {
            PAGO_REGISTRADO -> {
                if (recibo.recibo.tipoPago != NORMAL) return "un PAGO_REGISTRADO es de un recibo NORMAL, y el $numero es ${recibo.recibo.tipoPago}"
                val ordenes =
                    recibo.lineas.map { linea ->
                        val orden = linea.orden ?: return "el recibo $numero tiene una línea sin orden"
                        val actualizadoA = recibo.actualizadoDeLasOrdenes[orden] ?: return "la orden $orden del recibo $numero ya no existe"
                        OrdenDeCobro(
                            id = orden,
                            sistemaOrigen = linea.sistemaOrigen,
                            referenciaExterna = linea.referenciaExterna,
                            importe = linea.monto,
                            actualizadoA = actualizadoA
                        )
                    }
                if (ordenes.isEmpty()) return "el recibo $numero no tiene líneas"
                cuerpoPagoRegistrado(UUID.fromString(evento.eventoId), recibo.recibo, ordenes)
            }
            PAGO_ANULADO -> {
                val anulacion = recibo.anulacion ?: return "el recibo $numero no tiene su anulacion_recibo"
                val registrado =
                    recibo.eventos.firstOrNull { it.tipo == PAGO_REGISTRADO }
                        ?: return "el recibo $numero no tiene el PAGO_REGISTRADO que esta anulación deshace"
                cuerpoPagoAnulado(UUID.fromString(evento.eventoId), registrado.eventoId, recibo.recibo, anulacion.motivo.orEmpty(), anulacion.fecha!!)
            }
            else -> return "el tipo ${enUnaLinea(tipo)} no es un evento de pago"
        }
    val guardado =
        try {
            JSON.readTree(evento.cuerpo.orEmpty()).takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        } ?: return "el cuerpo no es un objeto JSON"
    val diferencias = diferentes(normalizado(guardado), normalizado(JSON.readTree(esperado)))
    return if (diferencias.isEmpty()) null else "el cuerpo no es el que se compone de su recibo: difiere en ${diferencias.joinToString(", ")}"
}

// las órdenes, por ordenId: su orden en el cuerpo es el de la petición, que no se guarda
private fun normalizado(cuerpo: JsonNode): JsonNode {
    val copia = cuerpo.deepCopy()
    val ordenes = copia.get("ordenes")
    if (copia is ObjectNode && ordenes != null && ordenes.isArray) {
        copia.set("ordenes", JSON.createArrayNode().addAll(ordenes.toList().sortedBy { it.get("ordenId")?.asString() ?: "" }))
    }
    return copia
}

// las claves de primer nivel en que difieren: lo que el responsable lee en ultimo_error, sin los valores (los
// escribió quien forjó el evento)
private fun diferentes(
    guardado: JsonNode,
    esperado: JsonNode
): List<String> {
    val claves = (guardado.propertyNames() + esperado.propertyNames()).toSortedSet()
    return claves.filter { guardado.get(it) != esperado.get(it) }.map { "«${enUnaLinea(it)}»" }
}

// un valor en una sola línea: lo que se interpola en un mensaje que acaba en el registro no puede partirlo en dos
fun enUnaLinea(valor: Any?): String = valor.toString().replace(Regex("\\s+"), " ")
