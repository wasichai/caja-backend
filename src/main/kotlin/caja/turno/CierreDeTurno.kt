package caja.turno

// el estado del turno (CierreDeTurno, EstadoDeTurno, TipoDeMovimientoDeTurno y SituacionDelCajero de caja, V32, #36).
//
// SOLO SE AGREGA (regla 4): un cierre no se modifica ni se borra; se reversa con otro. el cierre_turno firmado a las
// 13:00 sigue diciendo lo que decía aunque después se reverse: la reversion_cierre es una fila NUEVA que lo deja sin
// efecto y reabre el turno, y las dos juntas cuentan lo que pasó. por eso el estado del turno no es una columna (habría
// que actualizarla): se DERIVA del último movimiento por secuencia. la secuencia es común a los dos objetos y la
// serializa el candado del turno.
//
// FUNCIÓN PURA (regla 6): sin Spring, sin reloj y sin base (PurezaDelTurnoTest)

// los dos actos que caben sobre un turno: el cierre congela su arqueo y lo deja cerrado; la reversión deja sin efecto
// el cierre vigente y lo reabre. es la única forma de volver a cobrar ese día: un turno es único por caja, cajero y día
enum class TipoDeMovimiento {
    CIERRE,
    REVERSION;

    val cierra: Boolean get() = this == CIERRE
}

// un movimiento del turno: el id del cierre_turno o de la reversion_cierre, su tipo y su lugar en la historia, desde 1
data class Movimiento(
    val id: String,
    val tipo: TipoDeMovimiento,
    val secuencia: Long
)

enum class EstadoDelTurno {
    ABIERTO,
    CERRADO;

    companion object {
        // el estado tras esa historia: el del ÚLTIMO movimiento, no la cuenta de cierres contra reversiones (un turno
        // alterna los dos, y contar daría la respuesta correcta por casualidad). sin movimientos, abierto
        fun de(historia: List<Movimiento>): EstadoDelTurno = if (ordenada(historia).lastOrNull()?.tipo?.cierra == true) CERRADO else ABIERTO
    }
}

// el cierre que sigue en pie tras esa historia, o null: el último movimiento, si es un cierre
fun cierreVigente(historia: List<Movimiento>): Movimiento? = ordenada(historia).lastOrNull()?.takeIf { it.tipo.cierra }

// la secuencia del próximo movimiento, sea cierre o reversión: los que hay más uno
fun siguienteSecuencia(historia: List<Movimiento>): Long = ordenada(historia).size + 1L

// la historia por secuencia, comprobada: las secuencias van de 1 a n sin huecos ni repetidas, y alternan cierre y
// reversión empezando por un cierre (una reversión nombra el cierre vigente; dos cierres seguidos serían dos arqueos
// vigentes sobre el mismo dinero). el candado del turno lo garantiza; una historia rota no se interpreta
private fun ordenada(historia: List<Movimiento>): List<Movimiento> {
    val enOrden = historia.sortedBy { it.secuencia }
    enOrden.forEachIndexed { i, movimiento ->
        check(movimiento.secuencia == i + 1L) {
            "La historia del turno está rota: el movimiento ${movimiento.id} tiene la secuencia ${movimiento.secuencia} en el lugar ${i + 1}"
        }
        val esperado = if (i % 2 == 0) TipoDeMovimiento.CIERRE else TipoDeMovimiento.REVERSION
        check(movimiento.tipo == esperado) {
            "La historia del turno está rota: el movimiento ${movimiento.id} (secuencia ${movimiento.secuencia}) es ${movimiento.tipo} " +
                "y tocaba $esperado"
        }
    }
    return enOrden
}

// en qué situación está un cajero en su día (#97 de caja): no es un booleano, porque «no abrió» y «ya cerró» se
// arreglan distinto (al que cerró no le falta abrir, le falta reversar), y con turno abierto en dos ventanillas no se
// elige una: arquear «la primera» sería arquear una por otra
enum class SituacionDelCajero {
    SIN_ABRIR,
    ABIERTO,
    CERRADO,
    VARIOS_ABIERTOS;

    companion object {
        // estados: el de cada turno del cajero ese día, en todas sus ventanillas
        fun de(estados: List<EstadoDelTurno>): SituacionDelCajero {
            val abiertos = estados.count { it == EstadoDelTurno.ABIERTO }
            return when {
                abiertos > 1 -> VARIOS_ABIERTOS
                abiertos == 1 -> ABIERTO
                estados.isEmpty() -> SIN_ABRIR
                else -> CERRADO
            }
        }
    }
}
