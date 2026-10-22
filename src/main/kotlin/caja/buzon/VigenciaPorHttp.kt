package caja.buzon

import caja.cobro.LARGO_MOTIVO_RETIRO
import caja.cobro.Vigencia
import caja.cobro.VigenciaEnElOrigen
import caja.modelo.OrdenDeCobro
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

// la comprobación en línea por http (caja-backend#42): POST {url}/ordenes/vigencia al destino del buzón de ese sistema,
// con las referencias y los importes que se van a cobrar. solo si el destino lo pide (verificar): sin destino, o con
// verificar=false, no se pregunta nada y se cobra como siempre
@Component
class VigenciaPorHttp(
    private val propiedades: PropiedadesDelBuzon,
    private val cliente: ClienteDelSistemaDeOrigen
) : VigenciaEnElOrigen {
    override suspend fun consultar(
        sistema: String,
        ordenes: List<OrdenDeCobro>
    ): Vigencia {
        val destino = propiedades.destinos[sistema]
        if (destino == null || !destino.verificar) return Vigencia.NoSeConsulta
        val lectura = cliente.preguntar(sistema, RUTA_VIGENCIA, preguntaDeVigencia(ordenes))
        return vigenciaDe(ordenes, lectura, cobrar = destino.sinRespuesta == PropiedadesDelBuzon.SinRespuesta.COBRAR)
    }
}

// el buzón de preguntas del sistema de origen, bajo su url
const val RUTA_VIGENCIA = "/ordenes/vigencia"

private val JSON: JsonMapper = JsonMapper.builder().build()

// la pregunta: {"ordenes":[{"referencia_externa":"…","importe":"12.50"}]}, con el importe en cadena, como viaja todo
// importe de caja
fun preguntaDeVigencia(ordenes: List<OrdenDeCobro>): String =
    JSON.writeValueAsString(
        mapOf(
            "ordenes" to ordenes.map { linkedMapOf("referencia_externa" to it.referenciaExterna, "importe" to it.importe!!.toPlainString()) }
        )
    )

// lo que quiere decir la respuesta. un 200 con {"ordenes":[{"referencia_externa","vigente","importe","motivo"}]} que
// nombra cada orden pedida es Contesto: no vigente, o vigente con otro importe (comparado con compareTo), no se cobra.
// cualquier otra cosa (otro código, un cuerpo ilegible, una referencia que falta) es NoContesta, y cobrar es lo que
// diga la configuración del destino. una respuesta a medias no autoriza a cobrar lo que calló. el cuerpo se lee sin
// tachar; lo que se guarda (el motivo, que acaba en motivo_retiro) o se cuenta (el porqué de NoContesta) va tachado
fun vigenciaDe(
    ordenes: List<OrdenDeCobro>,
    lectura: Lectura,
    cobrar: Boolean
): Vigencia {
    val respuesta =
        when (lectura) {
            Lectura.SinDireccion -> return Vigencia.NoContesta("no tiene url configurada", cobrar)
            is Lectura.NoContesta -> return Vigencia.NoContesta("no contesta: ${lectura.motivo}", cobrar)
            is Lectura.Contesto -> lectura
        }
    if (respuesta.estado != 200) return Vigencia.NoContesta("contestó ${respuesta.estado} ${contestado(respuesta.tachado(respuesta.cuerpo), null)}", cobrar)
    val porReferencia =
        leerVigencias(respuesta.cuerpo)
            ?: return Vigencia.NoContesta("contestó algo que no se entiende ${contestado(respuesta.tachado(respuesta.cuerpo), null)}", cobrar)
    val noVigentes = linkedMapOf<String, String>()
    for (orden in ordenes) {
        val dicho =
            porReferencia[orden.referenciaExterna]
                ?: return Vigencia.NoContesta("no dijo nada de la referencia ${orden.referenciaExterna}", cobrar)
        when {
            !dicho.vigente ->
                noVigentes[orden.id!!] =
                    recortarMotivo("el origen dice: ${limpio(dicho.motivo?.let(respuesta::tachado)) ?: "ya no está vigente"}")
            dicho.importe != null && dicho.importe.compareTo(orden.importe) != 0 ->
                noVigentes[orden.id!!] = recortarMotivo("el origen dice otro importe: ${dicho.importe.toPlainString()}")
        }
    }
    return Vigencia.Contesto(noVigentes)
}

private class Dicho(
    val vigente: Boolean,
    val importe: BigDecimal?,
    val motivo: String?
)

// cada referencia con lo que el origen dijo de ella (la primera vez que la nombra), o null si el cuerpo no cumple el
// contrato: un vigente que no es booleano o un importe que no es una cifra no se adivinan
private fun leerVigencias(cuerpo: String): Map<String, Dicho>? {
    val raiz = runCatching { JSON.readTree(cuerpo) }.getOrNull() ?: return null
    val lista = raiz["ordenes"]?.takeIf { it.isArray } ?: return null
    val leidas = linkedMapOf<String, Dicho>()
    for (nodo in lista) {
        val referencia = nodo["referencia_externa"]?.takeIf { it.isString }?.asString() ?: return null
        val vigente = nodo["vigente"]?.takeIf { it.isBoolean }?.asBoolean() ?: return null
        val importe = nodo["importe"]?.let(::cifra)
        if (importe == null && nodo["importe"]?.isNull == false) return null
        val motivo = nodo["motivo"]?.takeIf { it.isString }?.asString()
        leidas.putIfAbsent(referencia, Dicho(vigente, importe, motivo))
    }
    return leidas
}

private fun cifra(nodo: JsonNode): BigDecimal? =
    when {
        nodo.isString -> nodo.asString().trim().toBigDecimalOrNull()
        nodo.isNumber -> nodo.decimalValue()
        else -> null
    }

// el motivo del origen en una línea: sin caracteres de control ni espacios de más. null si no dijo nada
private fun limpio(motivo: String?): String? =
    motivo
        ?.codePoints()
        ?.map { if (Character.isISOControl(it)) ' '.code else it }
        ?.toArray()
        ?.let { String(it, 0, it.size) }
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.ifEmpty { null }

// lo que cabe en motivo_retiro, contado en code points como lo cuenta la plataforma. el corte se ve («…»)
private fun recortarMotivo(texto: String): String {
    if (texto.codePointCount(0, texto.length) <= LARGO_MOTIVO_RETIRO) return texto
    return texto.substring(0, texto.offsetByCodePoints(0, LARGO_MOTIVO_RETIRO - 1)) + "…"
}
