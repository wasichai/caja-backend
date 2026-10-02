package caja.turno

import caja.cobro.FORMAS_DE_PAGO
import java.math.BigDecimal
import java.time.LocalDate
import caja.cobro.produceEvento as avisaAlOrigen

// el arqueo de un turno (ArqueoDelTurno, LineaDeArqueo y ReciboDelTurno de caja, #36, RF-087): lo cobrado, lo anulado y
// el neto, forma de pago por forma de pago, con lo que el cajero declaró haber contado.
//
// FUNCIÓN PURA (regla 6): se arma con los recibos del turno, lo declarado y la fecha, y nada más. sin Spring, sin reloj
// y sin base (PurezaDelTurnoTest lo vigila): el arqueo de un turno de 2026 vuelve a dar el mismo céntimo en 2036.
//
// NI UN REDONDEO, y no es un olvido (D-03d sigue abierta): no hay ninguna división. cada recibo tiene una sola forma de
// pago y su total va entero a una línea; sumar importes no crea decimales, así que la suma de las partes es el total
// exacto y no hay céntimo huérfano. las cifras salen con la escala con la que llegaron, nunca reescaladas

// el cero de las cifras del arqueo, con la escala de un importe: una línea sin declarado dice 0.00, no 0
private val CERO = BigDecimal("0.00")

private fun suma(cifras: List<BigDecimal>): BigDecimal = cifras.fold(CERO, BigDecimal::add)

private fun formaConocida(forma: String) {
    require(forma in FORMAS_DE_PAGO) { "'$forma' no es una forma de pago: una de ${FORMAS_DE_PAGO.joinToString(", ")}" }
}

// por qué las cifras de un recibo no se pueden contar, o null si se pueden: un total o una anulación en negativo, o una
// anulación mayor que el total. la cobranza y la anulación no las escriben; llegan por la API genérica de wasichai
// (wasichai#15) o por la base. la misma regla vale para el arqueo y para la recaudación
fun defectoDeLasCifras(
    total: BigDecimal,
    anulado: BigDecimal
): String? =
    when {
        total.signum() < 0 -> "su total es negativo (${total.toPlainString()}): un recibo no cobra en negativo"
        anulado.signum() < 0 -> "su anulación devolvió un importe negativo (${anulado.toPlainString()})"
        anulado > total ->
            "cobró ${total.toPlainString()} y su anulación devolvió ${anulado.toPlainString()}: el acta congela el total del recibo, no otra cifra"
        else -> null
    }

// por qué un recibo no puede entrar en un arqueo, o null si puede: sus cifras, o una forma de pago que no existe
fun defectoDelRecibo(
    formaPago: String,
    total: BigDecimal,
    anulado: BigDecimal
): String? =
    if (formaPago !in FORMAS_DE_PAGO) {
        "'$formaPago' no es una forma de pago: una de ${FORMAS_DE_PAGO.joinToString(", ")}"
    } else {
        defectoDeLasCifras(total, anulado)
    }

// un recibo que no se puede contar, con su porqué: queda fuera de las cifras y se nombra. nunca tumba el arqueo entero,
// ni bloquea el cierre, ni el reporte de un año: el dato roto se dice, no se cuenta ni se esconde
data class ReciboRoto(
    val numero: String,
    val motivo: String
)

// un recibo del turno como se leyó, sin juzgar todavía
data class FilaDeRecibo(
    val numero: String,
    val tipoPago: String,
    val formaPago: String,
    val total: BigDecimal,
    val anulado: BigDecimal
)

// los recibos leídos de un turno, partidos en los que el arqueo cuenta y los rotos con su porqué, en el orden leído
data class RecibosDelTurno(
    val contables: List<ReciboDelTurno>,
    val rotos: List<ReciboRoto>
) {
    companion object {
        fun de(filas: List<FilaDeRecibo>): RecibosDelTurno {
            val juzgadas = filas.map { it to defectoDelRecibo(it.formaPago, it.total, it.anulado) }
            return RecibosDelTurno(
                juzgadas.filter { it.second == null }.map { (f, _) -> ReciboDelTurno(f.numero, f.tipoPago, f.formaPago, f.total, f.anulado) },
                juzgadas.mapNotNull { (f, defecto) -> defecto?.let { ReciboRoto(f.numero, it) } }
            )
        }
    }
}

// un recibo del turno visto desde el arqueo: su número, su tipo de pago (decide si produjo evento), su forma de pago
// (decide en qué línea cae), su total congelado y lo que su anulación devolvió, que es el importe que el acta congeló y
// no el total releído. cero si sigue vigente. lo imposible lanza: quien lee de la base pasa por RecibosDelTurno
data class ReciboDelTurno(
    val numero: String,
    val tipoPago: String,
    val formaPago: String,
    val total: BigDecimal,
    val anulado: BigDecimal
) {
    init {
        defectoDelRecibo(formaPago, total, anulado)?.let { throw IllegalArgumentException("El recibo $numero no se puede contar: $it") }
    }

    val estaAnulado: Boolean get() = anulado.signum() != 0

    // lo que este recibo deja en el cajón
    val neto: BigDecimal get() = total.subtract(anulado)

    fun produceEvento(): Boolean = avisaAlOrigen(tipoPago)
}

// lo que una forma de pago movió en el turno y lo que el cajero declaró. la única cifra que puede ser negativa es la
// diferencia: negativa si falta dinero en el cajón, positiva si sobra
data class LineaDeArqueo(
    val formaPago: String,
    val cobrado: BigDecimal,
    val anulado: BigDecimal,
    val declarado: BigDecimal
) {
    init {
        formaConocida(formaPago)
        require(cobrado.signum() >= 0 && anulado.signum() >= 0 && declarado.signum() >= 0) {
            "Un arqueo no cuenta en negativo ($formaPago): la única cifra que puede serlo es la diferencia entre lo declarado y el neto"
        }
        require(anulado <= cobrado) {
            "En $formaPago se anuló ${anulado.toPlainString()} de ${cobrado.toPlainString()} cobrados: una anulación lleva el turno " +
                "del recibo, así que no puede sacar del cajón más de lo que entró en él"
        }
    }

    // lo que de verdad quedó en el cajón según el sistema
    val neto: BigDecimal get() = cobrado.subtract(anulado)

    // lo declarado menos el neto
    val diferencia: BigDecimal get() = declarado.subtract(neto)

    // la forma de pago que no se usó y que nadie declaró: no aporta una fila de ceros al acta
    val estaVacia: Boolean get() = cobrado.signum() == 0 && anulado.signum() == 0 && declarado.signum() == 0
}

// el arqueo: una línea por forma de pago con movimiento o con declarado, en el orden del enumerado (dos arqueos del
// mismo turno se dibujan igual), cuántos recibos se emitieron y cuántos se anularon, y la fecha a la que se leyó
class ArqueoDelTurno private constructor(
    val lineas: List<LineaDeArqueo>,
    val recibosEmitidos: Int,
    val recibosAnulados: Int,
    val aLaFecha: LocalDate
) {
    // los totales son la suma de las líneas, nunca una cifra aparte
    val totalCobrado: BigDecimal get() = suma(lineas.map { it.cobrado })
    val totalAnulado: BigDecimal get() = suma(lineas.map { it.anulado })
    val neto: BigDecimal get() = totalCobrado.subtract(totalAnulado)
    val totalDeclarado: BigDecimal get() = suma(lineas.map { it.declarado })

    // lo declarado menos el neto. NO se rechaza: un arqueo descuadrado es justo lo que hay que dejar escrito; si el
    // cierre exigiera cero, al cajero al que le faltan diez soles le bastaría declarar lo que dice el sistema
    val diferencia: BigDecimal get() = totalDeclarado.subtract(neto)

    fun cuadra(): Boolean = diferencia.signum() == 0

    companion object {
        // recibos: los del turno, con lo que devolvió su anulación. declarado: lo contado por forma de pago; lo que
        // falta cuenta como cero. aLaFecha: la fecha a la que se leen estas cifras (regla 9), que entra y no se lee
        fun de(
            recibos: List<ReciboDelTurno>,
            declarado: Map<String, BigDecimal>,
            aLaFecha: LocalDate
        ): ArqueoDelTurno {
            declarado.keys.forEach(::formaConocida)
            val porForma = recibos.groupBy { it.formaPago }
            val lineas =
                FORMAS_DE_PAGO
                    .map { forma ->
                        val suyos = porForma[forma].orEmpty()
                        LineaDeArqueo(forma, suma(suyos.map { it.total }), suma(suyos.map { it.anulado }), declarado[forma] ?: CERO)
                    }.filterNot { it.estaVacia }
            return ArqueoDelTurno(lineas, recibos.size, recibos.count { it.estaAnulado }, aLaFecha)
        }
    }
}

// lo recaudado partido en las dos mitades que se comprueban distinto (ArqueoDeTurno.Cuadre de caja): lo que se cobró
// contra una orden y produjo un evento que entregar, y lo que no avisa a nadie (las tasas). cada recibo aporta su neto:
// uno anulado, cero. las dos mitades suman el neto del arqueo, y el cierre lo exige: lo que detecta es un reparto entre
// las dos que se deje algún recibo fuera
data class Cuadre(
    val conEvento: BigDecimal,
    val sinEvento: BigDecimal
) {
    val total: BigDecimal get() = conEvento.add(sinEvento)

    fun sumaElNetoDe(arqueo: ArqueoDelTurno): Boolean = total.compareTo(arqueo.neto) == 0

    companion object {
        fun de(recibos: List<ReciboDelTurno>): Cuadre {
            val (conEvento, sinEvento) = recibos.partition { it.produceEvento() }
            return Cuadre(suma(conEvento.map { it.neto }), suma(sinEvento.map { it.neto }))
        }
    }
}
