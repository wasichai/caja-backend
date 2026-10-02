package caja.recaudacion

import caja.comun.Importe
import caja.turno.ArqueoRespuesta
import caja.turno.EstadoDelTurno
import caja.turno.ReciboRotoRespuesta
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant

// las formas de la recaudación y la conciliación. claves snake_case; toda cifra es un Importe con su fecha (regla 9)

// el recibo y su línea como los lee la recaudación, con su SELLO: el created_at de core (no es un campo del modelo;
// Registros lo agrega a lo leído), que es el comienzo de la transacción que los escribió
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReciboLeido(
    val id: String? = null,
    val numeroImpreso: String? = null,
    val turno: String? = null,
    val tipoPago: String? = null,
    val total: BigDecimal? = null,
    val createdAt: Instant? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LineaLeida(
    val id: String? = null,
    val recibo: String? = null,
    val tasa: String? = null,
    val sistemaOrigen: String? = null,
    val monto: BigDecimal? = null,
    val createdAt: Instant? = null
)

// el avance de recaudación del periodo, por origen, con lo que se pidió y la fecha a la que se leyó. turno: el arqueo
// en vivo del turno de hoy de esa caja y ese cajero, si se pidieron los dos; si no, null
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AvanceRespuesta(
    val desde: String,
    val hasta: String,
    val aLaFecha: String,
    val filas: List<FilaDeOrigenRespuesta>,
    val cobrado: Importe,
    val anulado: Importe,
    val neto: Importe,
    val turno: TurnoDelAvance?,
    // los recibos del rango que no se pudieron contar (escritos por fuera de caja), con su porqué: fuera de las cifras
    val recibosConDatosRotos: List<ReciboRotoRespuesta>
)

// origen: el sistema_origen de las órdenes, o TASA. null solo si el recibo no tiene líneas de su cobro (algo que la
// cobranza no escribe): el dato no existe y no se inventa
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FilaDeOrigenRespuesta(
    val origen: String?,
    val cobrado: Importe,
    val anulado: Importe,
    val neto: Importe
)

// el turno de hoy de la caja y el cajero pedidos, con su arqueo en vivo (el de GET /turnos/{id}/arqueo: sin declarado)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TurnoDelAvance(
    val turnoId: String,
    val caja: String,
    val cajero: String,
    val fecha: String,
    val estadoDelTurno: EstadoDelTurno,
    val arqueo: ArqueoRespuesta,
    val recibosConDatosRotos: List<ReciboRotoRespuesta>
)

// la recaudación por área y partida. neto_sin_partida es lo cobrado por órdenes, que no tiene partida: se publica
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PorAreaRespuesta(
    val desde: String,
    val hasta: String,
    val aLaFecha: String,
    val filas: List<FilaDePartidaRespuesta>,
    val neto: Importe,
    val netoSinPartida: Importe,
    val recibosConDatosRotos: List<ReciboRotoRespuesta>
)

// area, area_nombre y partida van null en lo que viene de una orden: el dato no existe y no se sustituye. concepto: el
// código de la tasa, o el sistema de origen de la orden
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FilaDePartidaRespuesta(
    val area: String?,
    val areaNombre: String?,
    val partida: String?,
    val concepto: String?,
    val cobrado: Importe,
    val anulado: Importe,
    val neto: Importe
)

// la conciliación del día: si cuadra, y una línea por sistema de destino con eventos ese día
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ConciliacionRespuesta(
    val fecha: String,
    val aLaFecha: String,
    val cuadra: Boolean,
    val lineas: List<LineaDeConciliacionRespuesta>
)

// lo que sale del buzón (los contadores y las cifras de los recibos) y lo que sale del origen (recibidos, aplicados,
// rechazados, importe_aplicado) con la diferencia. si el origen no contestó, lo suyo y la diferencia van en NULL y
// por_que_no_se_sabe dice por qué: nunca ceros
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LineaDeConciliacionRespuesta(
    val sistemaDestino: String,
    val registrados: Int,
    val anulados: Int,
    val enTransito: Int,
    val muertos: Int,
    val explicados: Int,
    val cobrado: Importe,
    val anulado: Importe,
    val neto: Importe,
    val recibidos: Long?,
    val aplicados: Long?,
    val rechazados: Long?,
    val importeAplicado: Importe?,
    val diferencia: Importe?,
    val porQueNoSeSabe: String?,
    val cuadra: Boolean
)
