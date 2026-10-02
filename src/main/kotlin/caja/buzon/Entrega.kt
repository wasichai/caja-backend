package caja.buzon

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

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

// el recibo de un evento, como lo lee el publicador para comprobar que el evento coincide con él: su tipo de pago, su
// total, el id de la orden de cada línea y si tiene su anulacion_recibo
data class ReciboDelEvento(
    val id: String,
    val numeroImpreso: String?,
    val tipoPago: String?,
    val total: BigDecimal?,
    val ordenes: List<String>,
    val anulado: Boolean
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

// por qué un evento no coincide con su recibo, o null si coincide. es la defensa (a) frente a la segunda puerta: un
// pago_evento escrito por la API genérica (POST /api/objects/pago_evento/records) no pasó por la cobranza ni por la
// anulación, y lo que diga su cuerpo no se envía si no es lo que dice su recibo. se comprueba lo que el destino
// imputa: el tipo, el total, las órdenes y, para una anulación, que la anulación exista. NO detecta la copia exacta de
// un evento legítimo con otro pagoId: eso lo ve el detector (GuardiaDeEscrituras)
fun incoherencia(
    tipo: String?,
    cuerpo: String?,
    recibo: ReciboDelEvento?
): String? {
    if (recibo == null) return "su recibo no existe"
    val arbol =
        try {
            JSON.readTree(cuerpo.orEmpty()).takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        } ?: return "el cuerpo no es un objeto JSON"
    val tipoDelCuerpo = texto(arbol, "tipo")
    if (tipoDelCuerpo != tipo) return "el tipo del cuerpo ($tipoDelCuerpo) no es el de la fila ($tipo)"
    val total = texto(arbol, "total")?.toBigDecimalOrNull()
    val totalCuadra = total != null && recibo.total != null && total.compareTo(recibo.total) == 0
    return when (tipo) {
        "PAGO_REGISTRADO" ->
            when {
                recibo.tipoPago != "NORMAL" -> "un PAGO_REGISTRADO es de un recibo NORMAL, y el ${recibo.numeroImpreso} es ${recibo.tipoPago}"
                !totalCuadra -> "el total del cuerpo (${texto(arbol, "total")}) no es el del recibo (${recibo.total?.toPlainString()})"
                ordenesDe(arbol) != recibo.ordenes.sorted() -> "las ordenes del cuerpo no son las de las líneas del recibo"
                else -> null
            }
        "PAGO_ANULADO" ->
            when {
                !recibo.anulado -> "el recibo ${recibo.numeroImpreso} no tiene su anulacion_recibo"
                !totalCuadra -> "el total del cuerpo (${texto(arbol, "total")}) no es el del recibo (${recibo.total?.toPlainString()})"
                else -> null
            }
        else -> "el tipo $tipo no es un evento de pago"
    }
}

private fun texto(
    nodo: JsonNode,
    campo: String
): String? = nodo.get(campo)?.takeIf { it.isValueNode && !it.isNull }?.asString()

// los ordenId del cuerpo, ordenados: dos listas con las mismas órdenes, repetidas igual, son iguales
private fun ordenesDe(cuerpo: JsonNode): List<String>? {
    val ordenes = cuerpo.get("ordenes")?.takeIf { it.isArray } ?: return null
    return ordenes.toList().map { texto(it, "ordenId") ?: return null }.sorted()
}
