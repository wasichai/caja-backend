package caja.buzon

import caja.comun.Importe
import caja.comun.enLima
import caja.modelo.PagoEvento
import caja.modelo.Recibo
import wasichai.core.common.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeParseException
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

// el día de los pagos a cuenta: obligatorio, como el de la conciliación (fechaDeLaConciliacion, que vive en recaudacion:
// buzon no la importa, porque recaudacion importa a buzon). sin él habría que elegir uno, y la hoja de cierre de ayer
// no se podría volver a leer
fun fechaDeLosPagosACuenta(valor: String?): LocalDate {
    val texto =
        valor?.trim()?.ifEmpty { null }
            ?: throw ValidationException("Falta la fecha", "fecha", "los pagos a cuenta son de un día: fecha=AAAA-MM-DD, obligatoria")
    return try {
        LocalDate.parse(texto)
    } catch (_: DateTimeParseException) {
        throw ValidationException("Fecha inválida", "fecha", "una fecha AAAA-MM-DD")
    }
}

// un pago a cuenta como sale por la api, con el número y el total de su recibo, este a la fecha del turno
fun pagoACuenta(
    evento: PagoEvento,
    recibo: Recibo?,
    dia: LocalDate
): PagoACuenta =
    PagoACuenta(
        pagoId = evento.eventoId!!,
        tipo = evento.tipo!!,
        recibo = recibo?.numeroImpreso,
        sistemaDestino = evento.sistemaDestino!!,
        total = recibo?.total?.let { Importe.de(it, dia) },
        motivoACuenta = evento.motivoACuenta,
        entregadoEn = evento.entregadoEn?.let(::enLima)
    )
