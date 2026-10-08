package caja.cobro

import caja.comun.CuerpoEstricto
import caja.comun.Importe
import caja.modelo.OrdenDeCobro
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

// las formas de la api de caja. las claves json son snake_case, las de los campos del modelo: un 400 de core o de las
// reglas (field = nombre del campo) cae sobre la misma clave que mandó el cliente

// lo que manda el sistema de origen. todo en cadena: el importe, las fechas y el id externo se leen en las reglas, que
// rechazan sobre su campo. no lleva tributo, ejercicio ni periodo (la frontera): cualquier clave que no esté aquí se
// anota y el alta la rechaza con un 400 que la nombra
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class NuevaOrden(
    val sistemaOrigen: String? = null,
    val referenciaExterna: String? = null,
    val concepto: String? = null,
    val detalle: String? = null,
    val importe: String? = null,
    val fechaExigibilidad: String? = null,
    val actualizadoA: String? = null,
    val pagadorDocumento: String? = null,
    val pagadorNombre: String? = null,
    val pagadorExternoId: String? = null,
    val observacion: String? = null
) : CuerpoEstricto()

// una orden como sale por la api: el importe con su fecha (regla 9). nueva solo en el alta: true si se creó ahora,
// false si ya estaba. va en el cuerpo además de en el código (201 o 200), para quien solo mire el cuerpo
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrdenRespuesta(
    val ordenId: String,
    val sistemaOrigen: String?,
    val referenciaExterna: String?,
    val concepto: String?,
    val detalle: String?,
    val importe: Importe,
    val fechaExigibilidad: String?,
    val pagadorDocumento: String?,
    val pagadorNombre: String?,
    val pagadorExternoId: Long?,
    val estado: String?,
    val observacion: String?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val nueva: Boolean? = null
) {
    companion object {
        fun de(
            orden: OrdenDeCobro,
            nueva: Boolean? = null
        ) = OrdenRespuesta(
            ordenId = orden.id!!,
            sistemaOrigen = orden.sistemaOrigen,
            referenciaExterna = orden.referenciaExterna,
            concepto = orden.concepto,
            detalle = orden.detalle,
            importe = Importe.de(orden.importe!!, orden.actualizadoA!!),
            fechaExigibilidad = orden.fechaExigibilidad?.toString(),
            pagadorDocumento = orden.pagadorDocumento,
            pagadorNombre = orden.pagadorNombre,
            pagadorExternoId = orden.pagadorExternoId,
            estado = orden.estado,
            observacion = orden.observacion,
            nueva = nueva
        )
    }
}

// una ventanilla del catálogo. la de baja sale también (activa false): el filtro de los recibos tiene que poder
// nombrarla. sin área, area_codigo y area_nombre van null: las cajas tributarias no tienen
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CajaEnLista(
    val codigo: String?,
    val nombre: String?,
    val serie: String?,
    val areaCodigo: String?,
    val areaNombre: String?,
    val activa: Boolean?
)

// un concepto que marca el cajero: el código de la tasa y cuántas veces. sin precio: una clave que no esté aquí se
// anota y se rechaza, y un precio o un importe dicen por qué
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class ConceptoPedido(
    val codigo: String? = null,
    val cantidad: String? = null
) : CuerpoEstricto()

// lo que manda la ventanilla para cobrar. el cajero y el día salen de la sesión: cajero y fecha_de_pago son opcionales
// y solo se admiten iguales a los de la sesión. todo en cadena, para que las reglas rechacen sobre su campo, y una
// clave desconocida se anota y se rechaza
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class NuevoCobro(
    val caja: String? = null,
    val cajero: String? = null,
    val formaPago: String? = null,
    val fechaDePago: String? = null,
    val ordenes: List<String>? = null,
    val observacion: String? = null
) : CuerpoEstricto()

// lo que manda la ventanilla para cobrar tasas. sin precio ni importe: el precio sale de la tarifa vigente (regla 5),
// y un importe o un precio en el cuerpo es un 400 que lo dice. el pagador puede ser anónimo: los tres opcionales. el
// cajero y la fecha de cobro salen de la sesión, como en el cobro de órdenes
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class NuevoCobroDeTasas(
    val caja: String? = null,
    val cajero: String? = null,
    val formaPago: String? = null,
    val fechaDeCobro: String? = null,
    val pagadorDocumento: String? = null,
    val pagadorNombre: String? = null,
    val pagadorExternoId: String? = null,
    val conceptos: List<ConceptoPedido>? = null,
    val observacion: String? = null
) : CuerpoEstricto()

// la vista previa de un cobro de órdenes: las mismas órdenes y la misma fecha que el cobro
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class VistaPreviaDeOrdenes(
    val ordenes: List<String>? = null,
    val fechaDePago: String? = null
) : CuerpoEstricto()

// la vista previa de un cobro de tasas: los mismos conceptos y la misma fecha que el cobro
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class VistaPreviaDeTasas(
    val conceptos: List<ConceptoPedido>? = null,
    val fechaDeCobro: String? = null
) : CuerpoEstricto()

// lo que costaría el cobro, sin cobrarlo: las líneas y el total como saldrían en el recibo (null si no hay ninguna
// línea), si se puede cobrar y, si no, por qué. un problema no es un error: va en motivos
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VistaPrevia(
    val lineas: List<LineaRespuesta>,
    val total: Importe?,
    val cobrable: Boolean,
    val motivos: List<String>
)

// una tasa vigente como la ofrece la ventanilla: su código, qué es, su área (el código), su partida y su precio con la
// fecha a la que rige
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TasaVigente(
    val codigo: String,
    val descripcion: String?,
    val area: String?,
    val partidaPresupuestal: String?,
    val precio: Importe
)

// lo que contesta el cobro: el recibo, el pagoId con el que el sistema de origen deduplicará, en qué está su entrega
// y si se emitió ahora (true, 201) o es el de un reenvío con la misma Idempotency-Key (false, 200). un recibo de tasas
// no tiene evento: pago_id null y estado_del_pago SIN_EVENTO
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CobroRespuesta(
    val recibo: ReciboRespuesta,
    val pagoId: String?,
    val estadoDelPago: String,
    val emitido: Boolean
)

// el recibo como sale por la api: el pagador tal como quedó guardado (la ventanilla muestra lo que dice el recibo, no
// lo que tecleó el cajero), el total con su fecha (regla 9) y las líneas
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReciboRespuesta(
    val numeroImpreso: String,
    val serie: String,
    val numero: Long,
    val cajero: String,
    val pagadorDocumento: String?,
    val pagadorNombre: String?,
    val pagadorExternoId: Long?,
    val formaPago: String,
    val tipoPago: String,
    val emitidoEn: String,
    val total: Importe,
    val lineas: List<LineaRespuesta>
)

// una línea del recibo o de la vista previa. la de una orden lleva su orden_id, su sistema, su detalle y su referencia;
// la de una tasa, su codigo, su cantidad y su precio_unitario (sus claves no salen en la de una orden)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LineaRespuesta(
    val ordenId: String?,
    val sistemaOrigen: String?,
    val concepto: String,
    val detalle: String?,
    val referenciaExterna: String?,
    val monto: Importe,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val codigo: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val cantidad: Long? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val precioUnitario: Importe? = null
)
