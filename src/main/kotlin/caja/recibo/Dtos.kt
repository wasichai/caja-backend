package caja.recibo

import caja.cobro.LineaRespuesta
import caja.comun.Importe
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.LocalDate

// las formas del recibo después de emitido. claves snake_case, las de los campos del modelo

// la anulación y la reimpresión como las guarda core. recibo, caja y turno son ids de relación
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnulacionRecibo(
    val id: String? = null,
    val recibo: String? = null,
    val reciboAnulado: String? = null,
    val caja: String? = null,
    val turno: String? = null,
    val fecha: LocalDate? = null,
    val motivo: String? = null,
    val autorizadoPor: String? = null,
    val documentoAutorizacion: String? = null,
    val importe: BigDecimal? = null,
    val usuario: String? = null,
    val observacion: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReimpresionRecibo(
    val id: String? = null,
    val recibo: String? = null,
    val fecha: LocalDate? = null,
    val resumen: String? = null,
    val usuario: String? = null,
    val observacion: String? = null
)

// lo que manda la ventanilla para anular. todo en cadena, para que las reglas rechacen sobre su campo; una clave
// desconocida se anota y se rechaza
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class PeticionDeAnulacion(
    val motivo: String? = null,
    val autorizadoPor: String? = null,
    val documentoAutorizacion: String? = null,
    val observacion: String? = null
) {
    @JsonIgnore
    val desconocidos: MutableList<String> = mutableListOf()

    @JsonAnySetter
    fun desconocido(
        nombre: String,
        @Suppress("UNUSED_PARAMETER") valor: Any?
    ) {
        desconocidos += nombre
    }
}

// lo que manda la ventanilla para un duplicado: por qué se reimprime (regla 10)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class PeticionDeDuplicado(
    val observacion: String? = null
) {
    @JsonIgnore
    val desconocidos: MutableList<String> = mutableListOf()

    @JsonAnySetter
    fun desconocido(
        nombre: String,
        @Suppress("UNUSED_PARAMETER") valor: Any?
    ) {
        desconocidos += nombre
    }
}

// una fila del listado: sin el desglose (quien lo quiere abre la ficha). el total con su fecha (regla 9); el estado y
// los duplicados se derivan de la anulación y de las reimpresiones
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReciboEnLista(
    val numeroImpreso: String,
    val emitidoEn: String,
    val pagadorDocumento: String?,
    val pagadorNombre: String?,
    val total: Importe,
    val formaPago: String,
    val duplicados: Long,
    val estado: String
)

// la ficha del recibo: lo congelado con sus líneas, su estado, cuántas veces se reimprimió y su anulación, si la tiene
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReciboEnFicha(
    val numeroImpreso: String,
    val serie: String,
    val numero: Long,
    val caja: String?,
    val cajero: String,
    val emitidoEn: String,
    val pagadorDocumento: String?,
    val pagadorNombre: String?,
    val pagadorExternoId: Long?,
    val formaPago: String,
    val tipoPago: String,
    val total: Importe,
    val observacion: String?,
    val lineas: List<LineaRespuesta>,
    val estado: String,
    val duplicados: Long,
    val anulacion: AnulacionEnFicha?
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnulacionEnFicha(
    val fecha: String,
    val motivo: String,
    val autorizadoPor: String?,
    val documentoAutorizacion: String?,
    val usuario: String
)

// el acta de la anulación: el recibo sigue como estaba, ahora ANULADO. el importe es su total congelado, con su fecha;
// pago_anulado_id es el pagoId del PAGO_ANULADO, o null en un recibo de tasas, que no avisa a nadie
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnulacionRespuesta(
    val numeroImpreso: String,
    val estado: String,
    val fecha: String,
    val motivo: String,
    val autorizadoPor: String?,
    val documentoAutorizacion: String?,
    val usuario: String,
    val importe: Importe,
    val pagoAnuladoId: String?
)
