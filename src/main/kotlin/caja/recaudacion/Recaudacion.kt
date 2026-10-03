package caja.recaudacion

import caja.cobro.PAGO_DE_TASA
import caja.recibo.diaPedido
import caja.recibo.rangoDeDias
import caja.turno.defectoDeLasCifras
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

// las agregaciones de la recaudación (ConsultaDeRecaudacion, RecaudacionDeTributo, RecaudacionDePartida y
// CriterioDeRecaudacion de caja, #36, RF-088, RF-089): cuánto entró, cuánto se anuló y cuánto queda neto, por origen y
// por área y partida.
//
// FUNCIONES PURAS (regla 6, PurezaDeLaRecaudacionTest): se arman con lo leído y nada más. sin Spring, sin reloj, sin
// base. y NI UN REDONDEO: repartir la recaudación no divide nada. cada recibo tiene un origen y su total va entero a
// él; cada línea, una partida o ninguna, y su monto va entero a ese grupo. la suma de las partes es el total al céntimo

// el cero de las cifras, con la escala de un importe: sin recibos, el neto dice 0.00, no 0
private val CERO = BigDecimal("0.00")

private fun suma(cifras: List<BigDecimal>): BigDecimal = cifras.fold(CERO, BigDecimal::add)

// el origen de los recibos de tasas (decisión 4): el recibo no tiene tributo, y lo que en caja guardaba la columna
// tributo es el sistema de origen de sus órdenes, o TASA (ADR-0045 §4)
const val ORIGEN_TASA = PAGO_DE_TASA

// el origen de un recibo: TASA si es de tasas; si es de órdenes, el sistema de origen de sus líneas (la cobranza exige
// una sola fuente por recibo). sin líneas del cobro, o con dos sistemas (algo que la cobranza no escribe), null: el dato
// no existe y no se inventa
fun origenDelRecibo(
    tipoPago: String,
    sistemasDeSusLineas: Collection<String?>
): String? = if (tipoPago == PAGO_DE_TASA) ORIGEN_TASA else sistemasDeSusLineas.filterNotNull().distinct().singleOrNull()

// las líneas que escribió el cobro de ese recibo: las que llevan su SELLO, el created_at que postgres dio con now() al
// comienzo de la transacción de la cobranza, la misma para el recibo y sus líneas (lo prueba BuzonApiTest). una
// linea_recibo agregada después, por fuera de la cobranza, lleva otro sello y no se cuenta: no
// cambia el origen del recibo ni infla la distribución
fun <T> delCobro(
    creadoEnElRecibo: Instant?,
    lineas: List<T>,
    creadoEn: (T) -> Instant?
): List<T> = lineas.filter { creadoEnElRecibo != null && creadoEn(it) == creadoEnElRecibo }

// un recibo del rango visto desde el avance: su origen, su total congelado y lo que su anulación devolvió (el importe
// que congeló el acta, el mismo que resta el arqueo del turno), o cero
data class ReciboRecaudado(
    val origen: String?,
    val total: BigDecimal,
    val anulado: BigDecimal
) {
    init {
        defectoDeLasCifras(total, anulado)?.let { throw IllegalArgumentException("La recaudación no cuenta este recibo: $it") }
    }
}

// por qué un recibo no se puede contar en la recaudación, o null si se puede: sus cifras (la regla del arqueo,
// defectoDeLasCifras) o una línea de su cobro sin monto o en negativo. un recibo roto queda fuera de las cifras, entero
// (sus líneas también: el avance y la recaudación por área cuentan los mismos recibos), y se nombra con este porqué.
// nunca tumba el reporte de todo un rango
fun defectoDeRecaudacion(
    total: BigDecimal,
    anulado: BigDecimal,
    montosDelCobro: List<BigDecimal?>
): String? =
    defectoDeLasCifras(total, anulado) ?: run {
        val rota = montosDelCobro.indexOfFirst { it == null || it.signum() < 0 }
        if (rota < 0) {
            null
        } else {
            montosDelCobro[rota]?.let { "una línea de su cobro es negativa (${it.toPlainString()}): una línea no cobra en negativo" }
                ?: "una línea de su cobro no tiene monto"
        }
    }

// lo recaudado por un origen: lo cobrado (anulados incluidos) y lo que de eso se anuló. se resta en vez de excluirse:
// un avance que solo mostrara el neto no podría explicar por qué ayer decía más que hoy
data class FilaDeOrigen(
    val origen: String?,
    val cobrado: BigDecimal,
    val anulado: BigDecimal
) {
    val neto: BigDecimal get() = cobrado.subtract(anulado)
}

// el avance de recaudación del periodo, por origen: una fila por origen con movimiento, de mayor a menor cobrado y,
// empatadas, por origen (el orden es total: dos lecturas iguales se dibujan igual); los totales son la suma de las filas
class Avance private constructor(
    val filas: List<FilaDeOrigen>
) {
    val cobrado: BigDecimal get() = suma(filas.map { it.cobrado })
    val anulado: BigDecimal get() = suma(filas.map { it.anulado })
    val neto: BigDecimal get() = cobrado.subtract(anulado)

    companion object {
        fun de(recibos: List<ReciboRecaudado>): Avance =
            Avance(
                recibos
                    .groupBy { it.origen }
                    .map { (origen, suyos) -> FilaDeOrigen(origen, suma(suyos.map { it.total }), suma(suyos.map { it.anulado })) }
                    .sortedWith(compareByDescending<FilaDeOrigen> { it.cobrado }.thenBy(nullsLast()) { it.origen })
            )
    }
}

// una línea del rango vista desde la distribución: el área y la partida de su tasa (las dos null en una línea de
// orden: la caja no sabe a qué partida va lo que el sistema de origen le mandó), su concepto (el código de la tasa, o
// el sistema de origen de la orden), su monto y si su recibo está anulado (la anulación es del recibo entero)
data class LineaRecaudada(
    val area: String?,
    val areaNombre: String?,
    val partida: String?,
    val concepto: String?,
    val monto: BigDecimal,
    val anulada: Boolean
) {
    init {
        require(monto.signum() >= 0) { "La recaudación no se cuenta en negativo" }
    }
}

// lo recaudado por un área, una partida y un concepto
data class FilaDePartida(
    val area: String?,
    val areaNombre: String?,
    val partida: String?,
    val concepto: String?,
    val cobrado: BigDecimal,
    val anulado: BigDecimal
) {
    val neto: BigDecimal get() = cobrado.subtract(anulado)

    // falso en lo que viene de una orden: es un hueco de datos, no un cero
    val tienePartida: Boolean get() = partida != null
}

// la recaudación por área y partida: REPARTE FILAS, no prorratea un total. el neto es la suma de los netos de las
// filas, y lo que no tiene partida se publica aparte (neto_sin_partida) en vez de esconderse: quien lea el reporte
// tiene que ver que la suma de las partidas no es la recaudación del periodo, y por qué
class Distribucion private constructor(
    val filas: List<FilaDePartida>
) {
    val neto: BigDecimal get() = suma(filas.map { it.neto })
    val netoSinPartida: BigDecimal get() = suma(filas.filterNot { it.tienePartida }.map { it.neto })

    companion object {
        fun de(lineas: List<LineaRecaudada>): Distribucion =
            Distribucion(
                lineas
                    .groupBy { listOf(it.area, it.areaNombre, it.partida, it.concepto) }
                    .map { (_, suyas) ->
                        val una = suyas.first()
                        FilaDePartida(
                            una.area,
                            una.areaNombre,
                            una.partida,
                            una.concepto,
                            suma(suyas.map { it.monto }),
                            suma(suyas.filter { it.anulada }.map { it.monto })
                        )
                    }.sortedWith(
                        compareByDescending<FilaDePartida> { it.cobrado }
                            .thenBy(nullsLast()) { it.area }
                            .thenBy(nullsLast()) { it.partida }
                            .thenBy(nullsLast()) { it.concepto }
                    )
            )
    }
}

// lo que llega en la petición

// el rango de días del TURNO, los dos incluidos. sin hasta, hoy; sin desde, el primero de enero del año de hasta (el
// ejercicio, como en caja): el avance de lo que va del año. una fecha mal escrita es 400 en su campo; un rango al revés,
// 400 en hasta. un filtro que no se entiende no es «todos»
data class Rango(
    val desde: LocalDate,
    val hasta: LocalDate
)

fun rangoPedido(
    desde: String?,
    hasta: String?,
    hoy: LocalDate
): Rango {
    val errores = mutableListOf<FieldViolation>()

    fun dia(
        valor: String?,
        campo: String
    ): LocalDate? =
        try {
            diaPedido(valor, campo)
        } catch (e: ValidationException) {
            errores += e.violations
            null
        }
    val primero = dia(desde, "desde")
    val ultimo = dia(hasta, "hasta")
    if (errores.isNotEmpty()) throw ValidationException("El rango no es válido", errores)
    val fin = ultimo ?: hoy
    val inicio = primero ?: fin.withDayOfYear(1)
    rangoDeDias(inicio, fin)
    return Rango(inicio, fin)
}

// el área, por su código o por la etiqueta del desplegable («113300 — SUBGERENCIA DE …»): se queda con el código, que
// es lo que area.codigo guarda. rechazar la etiqueta obligaría a la interfaz a partir la cadena por su cuenta
fun codigoDeArea(texto: String?): String? {
    val limpio = texto?.trim()?.ifEmpty { null } ?: return null
    return limpio.substringBefore('—').trim().ifEmpty { null }
}

// si una fila es del origen pedido: sin origen pedido, todas; si no, el mismo sin mirar mayúsculas (rentas, TASA)
fun esDelOrigen(
    origen: String?,
    pedido: String?
): Boolean {
    val buscado = pedido?.trim()?.ifEmpty { null } ?: return true
    return origen != null && origen.lowercase(Locale.ROOT) == buscado.lowercase(Locale.ROOT)
}
