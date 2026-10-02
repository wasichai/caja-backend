package caja.buzon

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
import java.math.BigDecimal
import java.time.Instant
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
// evento con los mismos compositores que la cobranza y la anulación. cada fila lleva su SELLO: el created_at (o, en la
// orden, el updated_at) que postgres le dio con now(), que es el comienzo de SU transacción
data class ReciboDelEvento(
    val recibo: Recibo,
    val creadoEn: Instant,
    val lineas: List<LineaDelEvento>,
    val ordenes: Map<String, OrdenDelEvento>,
    val anulacion: AnulacionDelEvento?,
    val eventos: List<EventoDelRecibo>
)

data class LineaDelEvento(
    val orden: String?,
    val sistemaOrigen: String?,
    val referenciaExterna: String?,
    val monto: BigDecimal?,
    val creadoEn: Instant
)

data class OrdenDelEvento(
    val actualizadoA: LocalDate?,
    val actualizadaEn: Instant
)

data class AnulacionDelEvento(
    val motivo: String?,
    val fecha: LocalDate?,
    val creadoEn: Instant
)

data class EventoDelRecibo(
    val id: UUID,
    val eventoId: String,
    val tipo: String?,
    val creadoEn: Instant
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
// genérica de wasichai, wasichai#15): un pago_evento, una linea_recibo o una anulacion_recibo que alguien escribió por
// POST, o un evento que editó por PUT /api/objects/.../records, no hacen salir de la caja un pago que no ocurrió.
//
// EL SELLO DE LA TRANSACCIÓN. postgres da a created_at el valor de now(), que es el comienzo de la transacción: todo lo
// que escribe UNA transacción de caja lleva el mismo instante (lo prueba BuzonApiTest). la cobranza escribe el recibo,
// sus líneas, el evento y las órdenes PAGADA en una; la anulación, su acta y el PAGO_ANULADO en otra. la API genérica no
// abre transacción (cada escritura se confirma sola) y no deja escribir created_at: lo que entra por ella lleva SIEMPRE
// otro sello. así que:
// - un PAGO_REGISTRADO tiene el sello de su recibo, y un PAGO_ANULADO el de su anulacion_recibo. si no, es una copia o un
//   evento forjado, y muere; uno forjado ANTES no le quita el lugar al legítimo, que sigue teniendo su sello;
// - el cuerpo se compone SOLO con las líneas que llevan el sello del recibo: una línea agregada después no cuenta (ni
//   mata al evento legítimo, ni hace pasar uno editado para incluirla), y esas líneas suman exactamente el total;
// - la fecha de cada orden (actualizadoA, que la línea no guarda) sale de la orden SOLO si su updated_at es el sello del
//   cobro: nadie la tocó desde entonces. si se tocó después (una anulación, un nuevo cobro, un cambio por la API
//   genérica), su valor de hoy ya no es el del cobro, y se toma el del propio cuerpo del evento para esa orden. ese es el
//   resto que queda abierto: quien pueda editar el cuerpo de un evento PENDIENTE (el UPDATE del supervisor) Y haya
//   tocado antes esa orden puede cambiar esa fecha y nada más.
//
// con eso, el cuerpo esperado se VUELVE A COMPONER con los MISMOS compositores que la cobranza (cuerpoPagoRegistrado) y
// la anulación (cuerpoPagoAnulado), con pagoId = el evento_id de la fila y pagoOriginalId = el del PAGO_REGISTRADO con
// el sello del cobro, y el cuerpo guardado tiene que ser igual, campo a campo. lo único que no se compara tal cual es el
// ORDEN de las órdenes: la cobranza las escribe en el orden de la petición, que no se guarda, así que se comparan
// ordenadas por ordenId. el sistema_destino de la fila tiene que ser el de esas líneas
fun incoherencia(
    evento: EventoDelBuzon,
    recibo: ReciboDelEvento?
): String? {
    if (recibo == null) return "su recibo no existe"
    val tipo = evento.tipo
    val numero = recibo.recibo.numeroImpreso
    val sello =
        when (tipo) {
            PAGO_REGISTRADO -> recibo.creadoEn
            PAGO_ANULADO -> recibo.anulacion?.creadoEn ?: return "el recibo $numero no tiene su anulacion_recibo"
            else -> return "el tipo ${enUnaLinea(tipo)} no es un evento de pago"
        }
    if (evento.creadoEn != sello) {
        val fuente = if (tipo == PAGO_REGISTRADO) "del recibo $numero" else "de la anulación del recibo $numero"
        return "es una copia o un evento forjado: no se escribió en la misma transacción $fuente (su created_at es ${evento.creadoEn}, el de su origen $sello)"
    }
    val lineas = recibo.lineas.filter { it.creadoEn == recibo.creadoEn }
    if (lineas.isEmpty()) return "el recibo $numero no tiene líneas de su cobro"
    val suma = lineas.map { it.monto ?: return "el recibo $numero tiene una línea sin monto" }.reduce(BigDecimal::add)
    if (recibo.recibo.total == null || suma.compareTo(recibo.recibo.total) != 0) {
        return "las líneas del cobro suman ${suma.toPlainString()} y el recibo $numero dice ${recibo.recibo.total?.toPlainString()}"
    }
    val sistemas = lineas.map { it.sistemaOrigen }.distinct()
    if (sistemas != listOf(evento.sistemaDestino)) {
        return "el sistema de destino (${enUnaLinea(evento.sistemaDestino)}) no es el de las órdenes del recibo (${enUnaLinea(sistemas)})"
    }
    val guardado =
        try {
            JSON.readTree(evento.cuerpo.orEmpty()).takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        } ?: return "el cuerpo no es un objeto JSON"
    val esperado =
        if (tipo == PAGO_REGISTRADO) {
            if (recibo.recibo.tipoPago != NORMAL) return "un PAGO_REGISTRADO es de un recibo NORMAL, y el $numero es ${recibo.recibo.tipoPago}"
            val ordenes =
                lineas.map { linea ->
                    val orden = linea.orden ?: return "el recibo $numero tiene una línea sin orden"
                    OrdenDeCobro(
                        id = orden,
                        sistemaOrigen = linea.sistemaOrigen,
                        referenciaExterna = linea.referenciaExterna,
                        importe = linea.monto,
                        actualizadoA =
                            recibo.ordenes[orden]?.takeIf { it.actualizadaEn == recibo.creadoEn }?.actualizadoA
                                ?: actualizadoDelCuerpo(guardado, orden)
                    )
                }
            cuerpoPagoRegistrado(UUID.fromString(evento.eventoId), recibo.recibo, ordenes)
        } else {
            val anulacion = recibo.anulacion!!
            val registrado =
                recibo.eventos.firstOrNull { it.tipo == PAGO_REGISTRADO && it.creadoEn == recibo.creadoEn }
                    ?: return "el recibo $numero no tiene el PAGO_REGISTRADO de su cobro, que esta anulación deshace"
            cuerpoPagoAnulado(UUID.fromString(evento.eventoId), registrado.eventoId, recibo.recibo, anulacion.motivo.orEmpty(), anulacion.fecha!!)
        }
    val diferencias = diferentes(normalizado(guardado), normalizado(JSON.readTree(esperado)))
    return if (diferencias.isEmpty()) null else "el cuerpo no es el que se compone de su recibo: difiere en ${diferencias.joinToString(", ")}"
}

// la fecha de una orden tal como la dice el cuerpo del evento, o null
private fun actualizadoDelCuerpo(
    cuerpo: JsonNode,
    orden: String
): LocalDate? =
    cuerpo
        .get("ordenes")
        ?.takeIf { it.isArray }
        ?.toList()
        ?.firstOrNull { it.get("ordenId")?.asString() == orden }
        ?.get("actualizadoA")
        ?.asString()
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

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
