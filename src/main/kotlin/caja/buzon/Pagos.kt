package caja.buzon

import caja.comun.enLima
import caja.modelo.PagoEvento
import wasichai.core.common.ValidationException
import java.util.UUID

// las reglas de la pantalla de los pagos sin entregar (PagoController y ExplicarPagoSinEntregar de caja)

const val MINIMO_EXPLICACION = 5

// el pago_id de la ruta: el pagoId del evento, el que dan la respuesta del cobro y GET /pagos/sin-entregar
fun pagoIdPedido(texto: String): String =
    runCatching { UUID.fromString(texto.trim()).toString() }
        .getOrElse { throw ValidationException("Pago inválido", "pago_id", "el pago_id de GET /api/caja/pagos/sin-entregar") }

// la explicación tiene que decir algo: es lo único que separa «alguien se hizo cargo» de «alguien lo apagó para poder
// cerrar la caja». recortada, de al menos 5 caracteres
fun explicacionDe(valor: String?): String {
    val texto = valor?.trim().orEmpty()
    if (texto.length < MINIMO_EXPLICACION) {
        throw ValidationException(
            "Falta la explicación",
            "explicacion",
            "qué pasó con el pago y qué se hizo: al menos $MINIMO_EXPLICACION caracteres que no sean espacios"
        )
    }
    return texto
}

// un evento como sale por la api, con el número impreso de su recibo
fun pagoDelBuzon(
    evento: PagoEvento,
    numero: String?
): PagoDelBuzon =
    PagoDelBuzon(
        pagoId = evento.eventoId!!,
        tipo = evento.tipo!!,
        destino = evento.sistemaDestino!!,
        recibo = numero,
        turnoId = evento.turno,
        estado = evento.estado!!,
        intentos = evento.intentos ?: 0,
        ultimoError = evento.ultimoError,
        creadoEn = evento.createdAt?.let(::enLima),
        entregadoEn = evento.entregadoEn?.let(::enLima),
        explicacion = evento.explicacion
    )
