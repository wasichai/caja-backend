package caja.turno

import caja.cobro.ENTEROS_DEL_IMPORTE
import caja.cobro.FORMAS_DE_PAGO
import org.springframework.http.HttpStatus
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

// las reglas sobre las peticiones del turno (CierreController, TurnoController y QuienYCuando de caja), y sus errores.
// cada regla devuelve el valor o lanza su 400 sobre el campo, o el 403 del cajero

// cierre_turno.motivo varchar(80) de caja: wasichai guarda TEXT sin largo
const val LARGO_MOTIVO_REVERSION = 80

// los estados de un pago_evento que impiden cerrar: el sistema de origen todavía no sabe que existe (PENDIENTE), o se
// dejó de intentar (MUERTO). ENTREGADO y EXPLICADO dejan cerrar
val PAGOS_SIN_ENTREGAR = setOf("PENDIENTE", "MUERTO")

// decimal llano: sin signo, sin exponente, sin punto suelto. [0-9] y no \d, que acepta dígitos de otros alfabetos
private val DECIMAL = Regex("[0-9]+(\\.[0-9]+)?")

// el día del turno que se cierra o se reversa (QuienYCuando de caja, HOY_O_UN_DIA_PASADO): hoy si no viene; si viene,
// hoy o un día pasado, nunca uno futuro. el turno que se quedó abierto ayer tiene que poder cerrarse, y sigue siendo el
// del cajero de la sesión: eso no deja cerrar el de otro
fun fechaDelTurno(
    valor: String?,
    hoy: LocalDate
): LocalDate {
    val texto = valor?.trim()?.ifEmpty { null } ?: return hoy
    val pedida =
        try {
            LocalDate.parse(texto)
        } catch (_: DateTimeParseException) {
            throw ValidationException("Fecha inválida", "fecha", "una fecha AAAA-MM-DD")
        }
    if (pedida.isAfter(hoy)) {
        throw ValidationException("Fecha futura", "fecha", "admite hoy ($hoy) o un día pasado, nunca uno futuro: omita el campo para hoy")
    }
    return pedida
}

// lo contado en el cajón, por forma de pago (CierreController.declaradoDe de caja). regla 1: cada cifra llega en
// cadena y se lee como BigDecimal, nunca como coma flotante; sin signo (no se declara en negativo), con 2 decimales y
// 13 enteros a lo sumo, tal como viene (sin redondeo ni escala inventada). la clave es la forma de pago, en mayúsculas;
// una vacía o null no declara nada, y lo que falta cuenta como cero. todo lo que falla, en un solo 400 en declarado
fun declaradoPedido(valores: Map<String, String?>?): Map<String, BigDecimal> {
    if (valores == null) return emptyMap()
    val errores = mutableListOf<FieldViolation>()
    val declarado = linkedMapOf<String, BigDecimal>()
    valores.forEach { (clave, valor) ->
        val forma = clave.trim().uppercase(Locale.ROOT)
        if (forma !in FORMAS_DE_PAGO) {
            errores += FieldViolation("declarado", "'$clave' no es una forma de pago: una de ${FORMAS_DE_PAGO.joinToString(", ")}")
            return@forEach
        }
        val texto = valor?.trim()?.ifEmpty { null } ?: return@forEach
        val problema =
            when {
                !DECIMAL.matches(texto) -> "un decimal sin signo escrito con punto, como 120.50"
                BigDecimal(texto).scale() > 2 -> "a lo sumo 2 decimales"
                BigDecimal(texto).let { it.precision() - it.scale() } > ENTEROS_DEL_IMPORTE -> "a lo sumo $ENTEROS_DEL_IMPORTE dígitos enteros"
                forma in declarado -> "la forma de pago viene dos veces"
                else -> null
            }
        if (problema != null) {
            errores += FieldViolation("declarado", "lo declarado en $forma ('$valor'): $problema")
        } else {
            declarado[forma] = BigDecimal(texto)
        }
    }
    if (errores.isNotEmpty()) throw ValidationException("Lo declarado no es válido", errores)
    return declarado
}

// reversar exige su motivo: es el sustento de reabrir una caja cuyo arqueo ya estaba firmado. obligatorio, no en blanco
// y de hasta 80
fun motivoDeReversion(valor: String?): String {
    val motivo =
        valor?.trim()?.ifEmpty { null }
            ?: throw ValidationException(
                "Falta el motivo",
                "motivo",
                "reversar un cierre exige su motivo: reabre una caja cuyo arqueo ya estaba firmado"
            )
    if (motivo.length > LARGO_MOTIVO_REVERSION) {
        throw ValidationException("Motivo demasiado largo", "motivo", "a lo sumo $LARGO_MOTIVO_REVERSION caracteres")
    }
    return motivo
}

// el cajero es quien firma la sesión (QuienYCuando de caja, #114). uno distinto en el cuerpo es 403, también para un
// supervisor: nadie cierra ni reversa el turno de otro cajero. ignorarlo en silencio dejaría al cliente creyendo que
// cerró el de otro
fun cajeroDelTurno(
    pedido: String?,
    deLaSesion: String
): String {
    val nombrado = pedido?.trim()?.ifEmpty { null }
    if (nombrado != null && nombrado != deLaSesion) {
        throw ForbiddenException(
            "El cajero es quien firma la sesión ('$deLaSesion') y la petición pide actuar sobre el turno de '$nombrado': nadie " +
                "cierra ni reversa el turno de otro cajero. Omita el campo 'cajero' o mande el suyo"
        )
    }
    return deLaSesion
}

// el turno del día es el de quien pregunta, hoy: un ?cajero= lo convertiría en «el turno de quien yo diga». cualquier
// parámetro es un 400 que lo nombra, en vez de ignorarse
fun sinParametros(nombres: Collection<String>) {
    if (nombres.isEmpty()) return
    throw ValidationException(
        "El turno del día no lleva parámetros",
        nombres.map { FieldViolation(it, "el turno del día es el de quien pregunta, hoy en Lima: quite este parámetro") }
    )
}

// el id de un turno, como lo publica GET /turnos/del-dia
fun turnoIdPedido(texto: String): String =
    runCatching { UUID.fromString(texto.trim()).toString() }
        .getOrElse { throw ValidationException("Turno inválido", "turno_id", "el turno_id que da GET /api/caja/turnos/del-dia") }

// un pago que impide cerrar, como lo dicen el 409 del cierre y el arqueo en vivo: su pagoId, su tipo y su estado
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PagoSinEntregar(
    val pagoId: String,
    val tipo: String,
    val estado: String
)

// 409: el turno tiene pagos que su sistema de origen todavía no conoce (ArqueoDeTurno.HayPagosSinEntregar de caja,
// ADR-0026 §4). un turno cerrado con uno de ellos deja el acta firmada, el cajón cuadrado y la deuda del administrado
// viva. NOMBRA LOS PAGOS uno a uno, en el detail y en pagos_sin_entregar (ProblemasDelTurno): quien no puede cerrar
// tiene que poder ir a mirar cuáles
class HayPagosSinEntregar(
    val pagos: List<PagoSinEntregar>
) : WasichaiException(
        HttpStatus.CONFLICT,
        "Hay pagos sin entregar: el turno no se puede cerrar mientras quede alguno que su sistema de origen todavía no conoce " +
            "(${pagos.size}: ${pagos.joinToString("; ") { "${it.pagoId} ${it.tipo} ${it.estado}" }}). Se resuelven entregándolos " +
            "o explicando cada uno por escrito"
    )
