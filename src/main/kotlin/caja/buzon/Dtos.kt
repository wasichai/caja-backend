package caja.buzon

import caja.comun.CuerpoEstricto
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

// un pago del buzón como lo ve el responsable de la conciliación (PagoResource de caja). recibo es el número impreso;
// creado_en es la hora del cobro, que dice cuánto lleva ese dinero sin que su sistema de origen lo sepa. las horas van
// en ISO con el offset de Lima
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PagoDelBuzon(
    val pagoId: String,
    val tipo: String,
    val destino: String,
    val recibo: String?,
    val turnoId: String?,
    val estado: String,
    val intentos: Long,
    val ultimoError: String?,
    val creadoEn: String?,
    val entregadoEn: String?,
    val explicacion: String?
)

// lo que manda quien se hace cargo de un pago MUERTO: qué pasó y qué se hizo (queda en el evento), y por qué se registra
// (regla 10, queda en la auditoría). lista blanca: una clave desconocida se anota y se rechaza
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class PeticionDeExplicacion(
    val explicacion: String? = null,
    val observacion: String? = null
) : CuerpoEstricto()
