package caja.cobro

import caja.comun.Importe
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
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

// una tasa del TUPA en una vigencia, como la guarda core. area es el id de su área. su importe es un dato registrado
// con su documento fuente, nunca un literal (regla 5)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Tasa(
    val id: String? = null,
    val codigo: String? = null,
    val descripcion: String? = null,
    val partidaPresupuestal: String? = null,
    val importe: BigDecimal? = null,
    val vigenciaDesde: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null,
    val documentoFuente: String? = null,
    val claveVigencia: String? = null,
    val area: String? = null
) {
    // Tasa.vigenteA de caja: rige ese día, ambos extremos incluidos; sin vigencia_hasta, no caduca. una vigencia que
    // termina antes de empezar es un dato mal cargado (import_tasas.py la rechaza, el admin no): 409, no se adivina
    fun vigenteA(fecha: LocalDate): Boolean {
        val desde = vigenciaDesde!!
        if (vigenciaHasta != null && vigenciaHasta.isBefore(desde)) {
            throw ConflictException(
                "La vigencia de la tasa $codigo ($claveVigencia) termina antes de empezar ($vigenciaHasta < $desde): es un dato mal " +
                    "cargado, corríjalo en el admin"
            )
        }
        return !fecha.isBefore(desde) && (vigenciaHasta == null || !fecha.isAfter(vigenciaHasta))
    }
}

// un concepto que marca el cajero: el código de la tasa y cuántas veces. sin precio: una clave que no esté aquí se
// anota y se rechaza, y un precio o un importe dicen por qué
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class ConceptoPedido(
    val codigo: String? = null,
    val cantidad: String? = null
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

// la vista previa de un cobro de órdenes: las mismas órdenes y la misma fecha que el cobro
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class VistaPreviaDeOrdenes(
    val ordenes: List<String>? = null,
    val fechaDePago: String? = null
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

// la vista previa de un cobro de tasas: los mismos conceptos y la misma fecha que el cobro
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class VistaPreviaDeTasas(
    val conceptos: List<ConceptoPedido>? = null,
    val fechaDeCobro: String? = null
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
// y si se emitió ahora (true, 201) o es el de un reenvío con la misma Idempotency-Key (false, 200). un recibo de tasas
// no tiene evento: pago_id null y estado_del_pago SIN_EVENTO
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CobroRespuesta(
    val recibo: ReciboRespuesta,
    val pagoId: String?,
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
