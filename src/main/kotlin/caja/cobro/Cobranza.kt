package caja.cobro

import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ForbiddenException
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

// las reglas puras de la cobranza (CobrarOrdenes de caja): el servicio las aplica dentro de la transacción, las pruebas
// las fijan. las de la petición lanzan un 400 sobre su campo, o el 403 del cajero

val FORMAS_DE_PAGO = listOf("EFECTIVO", "CHEQUE", "DEPOSITO", "TARJETA", "TRANSFERENCIA")
const val NORMAL = "NORMAL"
const val PAGO_REGISTRADO = "PAGO_REGISTRADO"
const val EVENTO_PENDIENTE = "PENDIENTE"

// lo que la ventanilla ve de un evento pendiente: cobrado, sin imputar todavía en el origen
const val EN_TRANSITO = "EN_TRANSITO"

const val LARGO_SERIE = 5
const val LARGO_CLAVE_IDEMPOTENCIA = 64

// el formato del papel (NumeroDeRecibo de caja): se compone aquí y en ningún otro sitio, porque dos formatos en dos
// pantallas hacen imposible buscar un recibo por lo que dice el papel
private const val FORMATO_NUMERO = "%s-%07d"

private val JSON: JsonMapper = JsonMapper.builder().build()

// el número como se imprime: 001-0000123. la serie de 1 a 5 caracteres, en mayúsculas; el correlativo desde 1. una
// serie que no cumple es un dato roto de la caja, no un error del cajero
fun numeroImpreso(
    serie: String,
    numero: Long
): String {
    val limpia = serie.trim().uppercase(Locale.ROOT)
    require(limpia.length in 1..LARGO_SERIE) { "La serie va de 1 a $LARGO_SERIE caracteres: '$limpia'" }
    require(numero > 0) { "El correlativo de un recibo empieza en 1; llegó $numero" }
    return String.format(Locale.ROOT, FORMATO_NUMERO, limpia, numero)
}

// el total es la suma de las líneas, exacta, nunca una cifra aparte: el papel y su desglose no pueden discrepar
fun totalDe(montos: List<BigDecimal>): BigDecimal {
    require(montos.isNotEmpty()) { "Un recibo sin líneas no documenta nada" }
    return montos.reduce(BigDecimal::add)
}

// un recibo cobra órdenes de un solo sistema: se anula entero, y anular uno mezclado mandaría dos anulaciones que
// podrían entregarse una sí y otra no
fun sistemaUnico(ordenes: List<OrdenDeCobro>): String {
    val sistemas = ordenes.mapNotNull { it.sistemaOrigen }.distinct()
    if (sistemas.size != 1) {
        throw ValidationException(
            "Un recibo cobra órdenes de un solo sistema",
            "ordenes",
            "hay órdenes de ${sistemas.joinToString(" y ") { "«$it»" }}: un recibo se anula entero, cóbrelas en recibos aparte"
        )
    }
    return sistemas.single()
}

// por qué una orden no se puede cobrar a esa fecha, o null si se puede
fun motivoNoCobrable(
    orden: OrdenDeCobro,
    fecha: LocalDate
): String? {
    if (orden.cobrableA(fecha)) return null
    val cabecera = "La orden ${orden.id} (${orden.sistemaOrigen}/${orden.referenciaExterna}) no se puede cobrar: "
    return cabecera +
        when (orden.estado) {
            PAGADA -> "ya se cobró con el recibo ${orden.recibo}. Si ese recibo se anula, la orden vuelve a PENDIENTE sola"
            ANULADA -> "el sistema que la emitió la retiró. Eso no se arregla en ventanilla"
            else -> "es exigible desde el ${orden.fechaExigibilidad} y se está cobrando al $fecha"
        }
}

// el nombre para el papel, o el motivo por el que no lo hay: nunca la cadena vacía, que se lee como un defecto de
// impresión (Pagador.nombreImpreso de caja)
fun nombreImpreso(
    nombre: String?,
    documento: String?
): String = nombre ?: documento ?: "— (no se identificó al pagador)"

// el cuerpo de PAGO_REGISTRADO (ComponedorDeEventosJson de caja, contrato rentas.json): congelado al cobrar, con los
// importes en cadena. no lleva imputación: la referencia de cada orden y su importe, y el origen decide qué extingue.
// ordenId es el uuid de la orden en cadena (en caja era un entero)
fun cuerpoPagoRegistrado(
    pagoId: UUID,
    recibo: Recibo,
    ordenes: List<OrdenDeCobro>
): String =
    JSON.writeValueAsString(
        linkedMapOf(
            "pagoId" to pagoId.toString(),
            "tipo" to PAGO_REGISTRADO,
            "sistemaOrigen" to sistemaUnico(ordenes),
            "recibo" to
                linkedMapOf(
                    "numero" to recibo.numeroImpreso,
                    "serie" to recibo.serie,
                    "fechaDePago" to recibo.actualizadoA.toString(),
                    "cajero" to recibo.cajero,
                    "formaDePago" to recibo.formaPago
                ),
            "pagador" to
                linkedMapOf(
                    "documento" to recibo.pagadorDocumento,
                    "nombre" to recibo.pagadorNombre,
                    "idExterno" to recibo.pagadorExternoId
                ),
            "total" to recibo.total!!.toPlainString(),
            "actualizadoA" to recibo.actualizadoA.toString(),
            "ordenes" to
                ordenes.map {
                    linkedMapOf(
                        "ordenId" to it.id,
                        "referenciaExterna" to it.referenciaExterna,
                        "importe" to it.importe!!.toPlainString(),
                        "actualizadoA" to it.actualizadoA.toString()
                    )
                }
        )
    )

// lo que llega en la petición

fun codigoDeCaja(valor: String?): String =
    valor?.trim()?.ifEmpty { null } ?: throw ValidationException("Falta un dato", "caja", "el código de la caja que cobra")

fun formaDePago(valor: String?): String {
    val forma = valor?.trim()?.uppercase(Locale.ROOT)
    if (forma !in FORMAS_DE_PAGO) {
        throw ValidationException("Forma de pago inválida", "forma_pago", "una de ${FORMAS_DE_PAGO.joinToString(", ")}")
    }
    return forma!!
}

// la grilla marca con casillas: una orden repetida solo viene de un cliente mal escrito, y cobrarla dos veces en el
// mismo recibo la cobraría dos veces de verdad
fun ordenesMarcadas(valores: List<String>?): List<UUID> {
    if (valores.isNullOrEmpty()) {
        throw ValidationException("Faltan las órdenes", "ordenes", "al menos una: un recibo sin líneas no documenta nada")
    }
    val ids =
        valores.map {
            runCatching { UUID.fromString(it.trim()) }
                .getOrElse { _ -> throw ValidationException("Orden inválida", "ordenes", "'$it' no es el id de una orden de cobro") }
        }
    if (ids.toSet().size != ids.size) {
        throw ValidationException("Orden repetida", "ordenes", "la misma orden viene marcada dos veces en el mismo recibo")
    }
    return ids
}

// la cabecera Idempotency-Key: opcional, de 1 a 64 caracteres
fun claveDeIdempotencia(valor: String?): String? {
    if (valor == null) return null
    val clave = valor.trim()
    if (clave.isEmpty() || clave.length > LARGO_CLAVE_IDEMPOTENCIA) {
        throw ValidationException("Idempotency-Key inválida", "Idempotency-Key", "de 1 a $LARGO_CLAVE_IDEMPOTENCIA caracteres")
    }
    return clave
}

// el día del cobro es hoy en Lima (QuienYCuando de caja, SOLO_HOY): si viene, solo se admite hoy. cobrar «ayer» abriría
// un turno de ayer
fun fechaDePago(
    valor: String?,
    hoy: LocalDate
): LocalDate {
    val texto = valor?.trim()?.ifEmpty { null } ?: return hoy
    val pedida =
        try {
            LocalDate.parse(texto)
        } catch (_: DateTimeParseException) {
            throw ValidationException("Fecha inválida", "fecha_de_pago", "una fecha AAAA-MM-DD")
        }
    if (pedida != hoy) {
        throw ValidationException("Solo se cobra hoy", "fecha_de_pago", "solo admite el día de hoy ($hoy): omita el campo")
    }
    return pedida
}

// el cajero es quien firma la sesión (QuienYCuando de caja, #114). uno distinto en el cuerpo es 403: ignorarlo en
// silencio dejaría al cliente creyendo que cobró por otro
fun cajeroDeLaSesion(
    pedido: String?,
    deLaSesion: String
): String {
    val nombrado = pedido?.trim()?.ifEmpty { null }
    if (nombrado != null && nombrado != deLaSesion) {
        throw ForbiddenException(
            "El cajero es quien firma la sesión ('$deLaSesion') y la petición pide cobrar como '$nombrado': nadie cobra en el turno " +
                "de otro cajero. Omita el campo 'cajero' o mande el suyo"
        )
    }
    return deLaSesion
}
