package caja.modelo

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.time.Instant

// el evento del buzón de salida: el aviso al sistema de origen de que se cobró, o se anuló, un recibo

// tipo_evento_pago
const val PAGO_REGISTRADO = "PAGO_REGISTRADO"
const val PAGO_ANULADO = "PAGO_ANULADO"

// estado_evento: nace PENDIENTE (el pago en tránsito) y queda ENTREGADO, MUERTO (no se pudo entregar: dinero
// cobrado sin registrar) o EXPLICADO (un MUERTO del que alguien se hizo cargo por escrito)
const val EVENTO_PENDIENTE = "PENDIENTE"
const val EVENTO_ENTREGADO = "ENTREGADO"
const val EVENTO_MUERTO = "MUERTO"
const val EVENTO_EXPLICADO = "EXPLICADO"

// el evento como lo guarda core. recibo y turno son ids de relación
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
    val intentos: Long? = null,
    val ultimoError: String? = null,
    val entregadoEn: Instant? = null,
    val explicacion: String? = null,
    // el pago que el origen recibió «a cuenta», y por qué (caja-backend#41). el nombre va escrito: el SnakeCase de jackson
    // no separa dos mayúsculas seguidas, y «motivoACuenta» se leería de «motivo_acuenta»
    val aCuenta: Boolean? = null,
    @param:JsonProperty("motivo_a_cuenta")
    val motivoACuenta: String? = null,
    // la hora en que se encoló, que es la del cobro: lo que lleva ese dinero en tránsito (no es un campo del modelo: es el
    // created_at de core, y una escritura no lo manda)
    val createdAt: Instant? = null
)
