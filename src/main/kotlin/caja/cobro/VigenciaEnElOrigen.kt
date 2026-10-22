package caja.cobro

import caja.comun.Importe
import caja.comun.enLima
import caja.modelo.OrdenDeCobro
import caja.modelo.Recibo
import org.springframework.http.HttpStatus
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.WasichaiException

// la comprobación en línea antes de cobrar (caja-backend#42): el puerto. cobro dice qué pregunta y qué hace con la
// respuesta; cómo se pregunta (por http, al destino del buzón) es de caja.buzon, que implementa este puerto. así cobro no
// importa buzon y el grafo de paquetes no tiene ciclos (DependenciasEntrePaquetesTest)
interface VigenciaEnElOrigen {
    // si el sistema de origen todavía cobra esas órdenes, todas de ese sistema y con ese importe. se llama SIEMPRE fuera
    // de una transacción: la red no retiene candados. nunca lanza: lo que pase es una Vigencia
    suspend fun consultar(
        sistema: String,
        ordenes: List<OrdenDeCobro>
    ): Vigencia
}

sealed interface Vigencia {
    // ese sistema no pide la comprobación (o no tiene destino): se cobra como siempre
    data object NoSeConsulta : Vigencia

    // el origen contestó de todas: las que ya no cobra, por id de orden, con el motivo que queda en motivo_retiro
    data class Contesto(
        val noVigentes: Map<String, String>
    ) : Vigencia

    // no contestó, o no se le entendió, o calló alguna: cobrar es lo que dice su sin-respuesta
    data class NoContesta(
        val motivo: String,
        val cobrar: Boolean
    ) : Vigencia
}

// lo que cabe en recibo.advertencia_origen
const val LARGO_ADVERTENCIA_ORIGEN = 400

// la advertencia que queda en el recibo cobrado sin la comprobación: el recibo es inmutable, así que se escribe al
// emitirlo. el corte se ve («…»)
fun advertenciaDelOrigen(
    sistema: String,
    motivo: String
): String {
    val texto = "No se pudo comprobar con «$sistema» que la deuda siguiera vigente; se cobró igual (sin-respuesta COBRAR): $motivo"
    return if (texto.length <= LARGO_ADVERTENCIA_ORIGEN) texto else texto.take(LARGO_ADVERTENCIA_ORIGEN - 1) + "…"
}

// el origen no contestó y su sin-respuesta es NO_COBRAR: 503, no se emitió nada. el cajero reintenta o cobra después
class OrigenSinRespuesta(
    sistema: String,
    motivo: String
) : WasichaiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "No se cobró: «$sistema» pide comprobar que la deuda sigue vigente antes de cobrarla y no contestó ($motivo). No se " +
            "emitió nada; vuelva a intentarlo en un momento"
    )

// un recibo cobrado sin la comprobación, para la hoja del día (GET /api/caja/cobros/sin-verificar): el origen recibirá
// el pago por el buzón, y si ya no lo cobraba lo tiene que resolver alguien
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CobroSinVerificar(
    val numeroImpreso: String,
    val cajero: String,
    val pagadorDocumento: String?,
    val pagadorNombre: String?,
    val emitidoEn: String,
    val total: Importe,
    val advertenciaOrigen: String
) {
    companion object {
        fun de(recibo: Recibo) =
            CobroSinVerificar(
                numeroImpreso = recibo.numeroImpreso!!,
                cajero = recibo.cajero!!,
                pagadorDocumento = recibo.pagadorDocumento,
                pagadorNombre = recibo.pagadorNombre,
                emitidoEn = enLima(recibo.emitidoEn!!),
                total = Importe.de(recibo.total!!, recibo.actualizadoA!!),
                advertenciaOrigen = recibo.advertenciaOrigen!!
            )
    }
}
