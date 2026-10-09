package caja.recibo

import caja.cobro.lineasEnOrden
import caja.cobro.numeroImpreso
import caja.comun.RECIBO
import caja.modelo.LineaRecibo
import caja.modelo.PAGO_ANULADO
import caja.modelo.Recibo
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ForbiddenException
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import wasichai.core.identity.AuthenticatedUser
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

// las reglas puras del recibo después de emitido (AnularRecibo, DuplicadoDeRecibo, MovimientoDeRecibo,
// CriterioDeRecibos y EstadoDeRecibo de caja): los servicios las aplican, las pruebas las fijan. el recibo no se
// corrige nunca: su estado se deriva de que exista su anulación, y sus duplicados se cuentan

// el estado del recibo no es una columna (V30 de caja): se deriva de que tenga su anulación. una columna que dijera
// EMITIDO para siempre mentiría, porque el recibo no se actualiza
const val EMITIDO = "EMITIDO"
const val ANULADO = "ANULADO"
val ESTADOS_DE_RECIBO = listOf(EMITIDO, ANULADO)

// la acción declarada de recibo (model.json) que anula el recibo de otro cajero: el privilegio ESPECIAL de caja
const val ANULAR_AJENO = "ANULAR_AJENO"

// los largos de recibo_movimiento de caja: wasichai guarda TEXT sin largo
const val LARGO_MOTIVO = 80
const val LARGO_AUTORIZADO = 80
const val LARGO_DOCUMENTO_AUTORIZACION = 40

private val JSON: JsonMapper = JsonMapper.builder().build()

fun estadoDelRecibo(anulado: Boolean): String = if (anulado) ANULADO else EMITIDO

// el número como lo dice el papel, serie-correlativo, en su forma canónica: «ab1-7» es el 'AB1-0000007'. lo que no
// tiene esa forma es un 400: no es un recibo que no existe, es una pregunta mal hecha
fun numeroDeRecibo(texto: String?): String {
    val limpio = texto?.trim()?.uppercase(Locale.ROOT).orEmpty()
    val guion = limpio.lastIndexOf('-')
    val serie = if (guion > 0) limpio.substring(0, guion) else ""
    val correlativo = if (guion > 0) limpio.substring(guion + 1) else ""
    val numero = correlativo.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
    if (serie.isEmpty() || serie.any(Char::isWhitespace) || numero == null || numero < 1) {
        throw ValidationException(
            "Número de recibo inválido",
            "numero_impreso",
            "va como está impreso en el papel, serie-correlativo: '001-0000123'"
        )
    }
    return try {
        numeroImpreso(serie, numero)
    } catch (_: IllegalArgumentException) {
        throw ValidationException("Número de recibo inválido", "numero_impreso", "la serie va de 1 a 5 caracteres")
    }
}

// los filtros del listado

// EMITIDO, ANULADO o ninguno (todos). otra palabra no es «todos»: un filtro que no se entiende y devuelve el listado
// entero es la lectura que quien filtra cree haber descartado (#544 de caja)
fun estadoPedido(valor: String?): String? {
    val estado = valor?.trim()?.uppercase(Locale.ROOT)?.ifEmpty { null } ?: return null
    if (estado !in ESTADOS_DE_RECIBO) {
        throw ValidationException("Estado inválido", "estado", "uno de ${ESTADOS_DE_RECIBO.joinToString(", ")}")
    }
    return estado
}

// lo que llega en la anulación y en el duplicado

// el sustento del acto, que se imprime en el duplicado: obligatorio, no en blanco, de hasta 80
fun motivoDeAnulacion(valor: String?): String =
    opcional(valor, "motivo", LARGO_MOTIVO)
        ?: throw ValidationException(
            "Falta el motivo",
            "motivo",
            "anular exige su motivo: sin él, el acta no dice por qué dejó de valer un papel que el pagador tiene en la mano"
        )

fun autorizadoPor(valor: String?): String? = opcional(valor, "autorizado_por", LARGO_AUTORIZADO)

// el memorando o la resolución que la sustenta
fun documentoDeAutorizacion(valor: String?): String? = opcional(valor, "documento_autorizacion", LARGO_DOCUMENTO_AUTORIZACION)

private fun opcional(
    valor: String?,
    campo: String,
    largo: Int
): String? =
    valor?.trim()?.ifEmpty { null }?.also {
        if (it.length > largo) throw ValidationException("Dato demasiado largo", campo, "a lo sumo $largo caracteres")
    }

// la anulación

// un recibo solo se anula el mismo día del pago, el día de su turno (RF-083). 422 y no 409: no es que hoy no se pueda,
// es que ese recibo no se podrá anular nunca más. lo que corresponde es una devolución
class FueraDelDiaDePago(
    numero: String,
    delPago: LocalDate,
    hoy: LocalDate
) : WasichaiException(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "El recibo $numero se cobró el $delPago y hoy es $hoy: un recibo solo se anula el mismo día del pago. Ese dinero ya cuadró en " +
            "el arqueo de su día; deshacerlo ahora dejaría un cierre diciendo una cifra y la caja otra. Lo que corresponde es una devolución"
    )

fun delMismoDia(
    numero: String,
    delTurno: LocalDate,
    hoy: LocalDate
) {
    if (delTurno != hoy) throw FueraDelDiaDePago(numero, delTurno, hoy)
}

// el recibo de otro cajero lo anula quien tiene ANULAR_AJENO sobre recibo (el supervisor, y ADMIN): toca el arqueo de un
// turno que no es el suyo. la función es pura: quien llama le dice si el usuario tiene la acción, que se comprueba con
// el objectId del recibo
fun puedeAnular(
    cajeroDelRecibo: String,
    usuario: AuthenticatedUser,
    numero: String,
    tieneAnularAjeno: Boolean
) {
    if (cajeroDelRecibo == usuario.email || tieneAnularAjeno) return
    throw ForbiddenException(
        "El recibo $numero lo cobró otro cajero ($cajeroDelRecibo): anularlo exige el permiso $ANULAR_AJENO sobre $RECIBO, que " +
            "tiene el supervisor de caja, porque toca el arqueo de su turno"
    )
}

// el cuerpo de PAGO_ANULADO (ComponedorDeEventosJson.pagoAnulado de caja), congelado al anular. pagoOriginalId es el
// pagoId del PAGO_REGISTRADO: el origen reversa los asientos de ese pago, y sin él tendría que buscarlos por el número
// del papel, que es texto y no una clave
fun cuerpoPagoAnulado(
    pagoId: UUID,
    pagoOriginalId: String,
    recibo: Recibo,
    motivo: String,
    fecha: LocalDate
): String =
    JSON.writeValueAsString(
        linkedMapOf(
            "pagoId" to pagoId.toString(),
            "tipo" to PAGO_ANULADO,
            "pagoOriginalId" to pagoOriginalId,
            "recibo" to
                linkedMapOf(
                    "numero" to recibo.numeroImpreso,
                    "serie" to recibo.serie,
                    "fechaDePago" to recibo.actualizadoA.toString(),
                    "cajero" to recibo.cajero,
                    "formaDePago" to recibo.formaPago
                ),
            "motivo" to motivo,
            "fecha" to fecha.toString(),
            "total" to recibo.total!!.toPlainString()
        )
    )

// el duplicado

// el SHA-256 de lo congelado en el recibo y sus líneas, en una representación canónica: claves en un orden fijo, las
// cifras con toPlainString, el instante en ISO y las líneas en el orden del papel. no se resumen los bytes del pdf,
// que no son deterministas (fechas de creación, subconjuntos de fuentes), ni lo que no está congelado en el recibo (el
// nombre de la caja, que se edita en el admin). si cambia entre dos reimpresiones, el duplicado ya no sale igual
fun resumenDelRecibo(
    recibo: Recibo,
    lineas: List<LineaRecibo>
): String {
    val canonico =
        linkedMapOf(
            "numero_impreso" to recibo.numeroImpreso,
            "serie" to recibo.serie,
            "numero" to recibo.numero,
            "caja" to recibo.caja,
            "turno" to recibo.turno,
            "cajero" to recibo.cajero,
            "pagador_documento" to recibo.pagadorDocumento,
            "pagador_nombre" to recibo.pagadorNombre,
            "pagador_externo_id" to recibo.pagadorExternoId,
            "emitido_en" to recibo.emitidoEn?.toString(),
            "forma_pago" to recibo.formaPago,
            "tipo_pago" to recibo.tipoPago,
            "total" to recibo.total?.toPlainString(),
            "actualizado_a" to recibo.actualizadoA?.toString(),
            "observacion" to recibo.observacion,
            "lineas" to
                lineasEnOrden(lineas).map {
                    linkedMapOf(
                        "orden" to it.orden,
                        "tasa" to it.tasa,
                        "sistema_origen" to it.sistemaOrigen,
                        "concepto" to it.concepto,
                        "detalle" to it.detalle,
                        "referencia_externa" to it.referenciaExterna,
                        "cantidad" to it.cantidad,
                        "precio_unitario" to it.precioUnitario?.toPlainString(),
                        "monto" to it.monto?.toPlainString()
                    )
                }
        )
    val bytes = JSON.writeValueAsString(canonico).toByteArray(Charsets.UTF_8)
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
