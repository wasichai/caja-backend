package caja.cobro

import caja.comun.Importe
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

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

// una orden de cobro como la guarda core. recibo es el id del que la cobró: PAGADA lo nombra (orden_recibo_ck)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrdenDeCobro(
    val id: String? = null,
    val sistemaOrigen: String? = null,
    val referenciaExterna: String? = null,
    val claveOrigen: String? = null,
    val concepto: String? = null,
    val detalle: String? = null,
    val importe: BigDecimal? = null,
    val fechaExigibilidad: LocalDate? = null,
    val actualizadoA: LocalDate? = null,
    val pagadorDocumento: String? = null,
    val pagadorNombre: String? = null,
    val pagadorExternoId: Long? = null,
    val estado: String? = null,
    val observacion: String? = null,
    val recibo: String? = null
) {
    // OrdenDeCobro.cobrableA de caja: pendiente y ya exigible a la fecha de pago
    fun cobrableA(fecha: LocalDate): Boolean = estado == PENDIENTE && fechaExigibilidad != null && !fecha.isBefore(fechaExigibilidad)
}

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

// area y caja como las guarda core. area es el id del área de la caja
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Area(
    val id: String? = null,
    val codigo: String? = null,
    val nombre: String? = null,
    val activa: Boolean? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Caja(
    val id: String? = null,
    val codigo: String? = null,
    val nombre: String? = null,
    val serie: String? = null,
    val activa: Boolean? = null,
    val area: String? = null
)

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

// el turno, el recibo, su línea y el evento como los guarda core. caja y turno son ids de relación
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Turno(
    val id: String? = null,
    val caja: String? = null,
    val cajero: String? = null,
    val fecha: LocalDate? = null,
    val abiertoEn: Instant? = null,
    val observacion: String? = null,
    val claveTurno: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Recibo(
    val id: String? = null,
    val serie: String? = null,
    val numero: Long? = null,
    val numeroImpreso: String? = null,
    val caja: String? = null,
    val turno: String? = null,
    val cajero: String? = null,
    val pagadorDocumento: String? = null,
    val pagadorNombre: String? = null,
    val pagadorExternoId: Long? = null,
    val emitidoEn: Instant? = null,
    val formaPago: String? = null,
    val tipoPago: String? = null,
    val total: BigDecimal? = null,
    val actualizadoA: LocalDate? = null,
    val claveIdempotencia: String? = null,
    val observacion: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LineaRecibo(
    val id: String? = null,
    val recibo: String? = null,
    val orden: String? = null,
    val tasa: String? = null,
    val sistemaOrigen: String? = null,
    val concepto: String? = null,
    val detalle: String? = null,
    val referenciaExterna: String? = null,
    val cantidad: Long? = null,
    val precioUnitario: BigDecimal? = null,
    val monto: BigDecimal? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PagoEvento(
    val id: String? = null,
    val eventoId: String? = null,
    val tipo: String? = null,
    val sistemaDestino: String? = null,
    val recibo: String? = null,
    val turno: String? = null,
    val cuerpo: String? = null,
    val estado: String? = null,
    val intentos: Long? = null
)

// lo que contesta el cobro: el recibo, el pagoId con el que el sistema de origen deduplicará, en qué está su entrega
// y si se emitió ahora (true, 201) o es el de un reenvío con la misma Idempotency-Key (false, 200)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CobroRespuesta(
    val recibo: ReciboRespuesta,
    val pagoId: String,
    val estadoDelPago: String,
    val emitido: Boolean
)

// el recibo como sale por la api: el total con su fecha (regla 9) y las líneas
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReciboRespuesta(
    val numeroImpreso: String,
    val serie: String,
    val numero: Long,
    val cajero: String,
    val formaPago: String,
    val tipoPago: String,
    val emitidoEn: String,
    val total: Importe,
    val lineas: List<LineaRespuesta>
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LineaRespuesta(
    val ordenId: String?,
    val sistemaOrigen: String?,
    val concepto: String,
    val detalle: String?,
    val referenciaExterna: String?,
    val monto: Importe
)
