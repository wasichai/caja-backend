package caja.recaudacion

import caja.buzon.EXPLICADO
import caja.buzon.Lectura
import caja.buzon.contestado
import caja.cobro.EVENTO_PENDIENTE
import caja.cobro.PAGO_REGISTRADO
import caja.recibo.PAGO_ANULADO
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

// la conciliación del día (ConciliacionDelDia, BuzonDeSalidaJdbc.recuentoDe y AbonosAplicadosHttp de caja, ADR-0026 §3).
// con la caja y el sistema de origen en dos bases, ningún cobro es atómico entre las dos: lo que sustituye a la
// atomicidad es ESTO. cada día se compara lo que la caja cobró con lo que el origen dice haber aplicado, y si no
// coinciden el día no cuadra.
//
// FALLA EN VOZ ALTA: si el origen no contesta, o no hay a dónde preguntarle, la línea sale SIN cifras del origen y CON
// su motivo. nunca ceros: un cero se leería como «no aplicaron nada», que es indistinguible de un día en que de verdad no
// se cobró, y la conciliación diría que cuadra. lo que la caja sabe sola (el recuento del buzón) lo dice igual: un
// origen caído deja la conciliación incompleta, no ciega.
//
// FUNCIONES PURAS (regla 6, PurezaDeLaRecaudacionTest): el día entra como argumento, y la red la pone otro

private val CERO = BigDecimal("0.00")

private val JSON: JsonMapper = JsonMapper.builder().build()

// decimal llano, sin signo ni exponente: lo aplicado no es negativo. [0-9] y no \d
private val DECIMAL = Regex("[0-9]+(\\.[0-9]+)?")

private const val MUERTO = "MUERTO"

// un pago_evento de un turno del día, con el total de su recibo: un evento no lleva importe propio, y sumar cifras de un
// JSON sería componer dinero fuera del sitio donde vive. lo cobrado y lo anulado salen del recibo
data class EventoDelDia(
    val sistema: String,
    val tipo: String,
    val estado: String,
    val totalDelRecibo: BigDecimal
)

// lo que la caja sabe sola de un sistema de destino en un día: cuántos pagos registró y anuló, en qué están, y por
// cuánto dinero (los totales de sus recibos)
data class RecuentoDelDia(
    val sistema: String,
    val registrados: Int,
    val anulados: Int,
    val enTransito: Int,
    val muertos: Int,
    val explicados: Int,
    val cobrado: BigDecimal,
    val anulado: BigDecimal
) {
    val neto: BigDecimal get() = cobrado.subtract(anulado)
}

// un recuento por sistema de destino con eventos ese día, por nombre (un total de rentas con mercados no se concilia
// contra nadie, y un día que cuadrara «en total» podría tener un sistema de más y otro de menos)
fun recuentosDe(eventos: List<EventoDelDia>): List<RecuentoDelDia> =
    eventos
        .groupBy { it.sistema }
        .map { (sistema, suyos) ->
            val registrados = suyos.filter { it.tipo == PAGO_REGISTRADO }
            val anulados = suyos.filter { it.tipo == PAGO_ANULADO }
            RecuentoDelDia(
                sistema = sistema,
                registrados = registrados.size,
                anulados = anulados.size,
                enTransito = suyos.count { it.estado == EVENTO_PENDIENTE },
                muertos = suyos.count { it.estado == MUERTO },
                explicados = suyos.count { it.estado == EXPLICADO },
                cobrado = registrados.fold(CERO) { total, it -> total.add(it.totalDelRecibo) },
                anulado = anulados.fold(CERO) { total, it -> total.add(it.totalDelRecibo) }
            )
        }.sortedBy { it.sistema }

// lo que el origen dice haber aplicado ese día: cuántos pagos le llegaron, cuántos imputó, cuántos no pudo imputar y
// esperan a alguien, y por cuánto dinero
data class AplicadoEnElOrigen(
    val recibidos: Long,
    val aplicados: Long,
    val rechazados: Long,
    val importeAplicado: BigDecimal
)

// una línea: un sistema de destino. O el origen contestó O hay un motivo por el que no se sabe: las dos cosas a la vez,
// o ninguna, dejarían la línea diciendo que cuadra sin poder saberlo
data class LineaDeConciliacion(
    val recuento: RecuentoDelDia,
    val aplicado: AplicadoEnElOrigen?,
    val porQueNoSeSabe: String?
) {
    init {
        require((aplicado == null) != porQueNoSeSabe.isNullOrBlank()) {
            "O el origen contestó, o hay un motivo por el que no: las dos cosas a la vez, o ninguna, dejan la línea diciendo que " +
                "cuadra sin poder saberlo"
        }
    }

    // lo que la caja cobró menos lo que el origen aplicó. null si el origen no contestó: nunca cero
    val diferencia: BigDecimal? get() = aplicado?.let { recuento.neto.subtract(it.importeAplicado) }

    // tres condiciones y no una, porque se arreglan de tres maneras: que no quede nada en tránsito ni muerto, que el
    // origen haya contestado, y que la diferencia sea cero sin rechazos. un día con la diferencia en cero y tres pagos en
    // tránsito NO cuadra: cuadra por casualidad, porque todavía no se aplicaron
    fun cuadra(): Boolean =
        recuento.enTransito == 0 &&
            recuento.muertos == 0 &&
            aplicado != null &&
            diferencia!!.signum() == 0 &&
            aplicado.rechazados == 0L
}

// el día cuadra si cuadran todas sus líneas. un día sin ningún cobro cuadra, y tiene razón en cuadrar
fun cuadraElDia(lineas: List<LineaDeConciliacion>): Boolean = lineas.all { it.cuadra() }

// la línea de un sistema con lo que contestó su origen a GET {url}/pagos/conciliacion?fecha=<dia>. la fecha con la que
// se pregunta es EXACTAMENTE la que se contó en la caja: si el origen contesta por otro día, no se sabe
fun lineaDe(
    recuento: RecuentoDelDia,
    dia: LocalDate,
    lectura: Lectura
): LineaDeConciliacion {
    val sistema = recuento.sistema
    val noContesto = "$sistema no contestó"
    return when (lectura) {
        Lectura.SinDireccion ->
            noSeSabe(
                recuento,
                "el destino $sistema no está configurado: falta caja.buzon.destinos.$sistema.url, así que no hay a quién preguntarle qué aplicó"
            )
        is Lectura.NoContesta -> noSeSabe(recuento, "$noContesto: ${lectura.motivo}")
        is Lectura.Contesto ->
            if (lectura.estado != 200) {
                noSeSabe(
                    recuento,
                    "$noContesto: a GET /pagos/conciliacion?fecha=$dia contestó ${lectura.estado}, ${contestado(lectura.cuerpo, token = null)}"
                )
            } else {
                try {
                    LineaDeConciliacion(recuento, aplicadoDe(lectura.cuerpo, dia), null)
                } catch (e: IllegalArgumentException) {
                    noSeSabe(recuento, "$noContesto una conciliación que se pueda leer: ${e.message}")
                } catch (e: JacksonException) {
                    noSeSabe(recuento, "$noContesto una conciliación que se pueda leer: ${e.javaClass.simpleName}")
                }
            }
    }
}

private fun noSeSabe(
    recuento: RecuentoDelDia,
    motivo: String
) = LineaDeConciliacion(recuento, null, motivo)

// lo que dice el cuerpo de un 200. cada cifra se exige: la que falta o no se entiende no se rellena con cero.
// importe_aplicado viaja en cadena (regla 1); se lee también importeAplicado, como lo contesta hoy rentas
private fun aplicadoDe(
    cuerpo: String,
    dia: LocalDate
): AplicadoEnElOrigen {
    val json =
        try {
            JSON.readTree(cuerpo)
        } catch (_: JacksonException) {
            null
        }
    require(json != null && json.isObject) { "su respuesta no es un objeto JSON: ${contestado(cuerpo, token = null)}" }
    // cada valor del otro sistema se nombra con toString(): asString() sobre un objeto o una lista lanza, y una respuesta
    // mal formada tiene que acabar en «no se sabe», no en un 500 de toda la conciliación
    json["fecha"]?.takeUnless { it.isNull }?.let { fecha ->
        require(fecha.isString && fecha.asString() == dia.toString()) { "contestó con la fecha $fecha y se le preguntó por el $dia" }
    }

    fun cuenta(campo: String): Long {
        val valor = json[campo]
        require(valor != null && valor.isIntegralNumber && valor.canConvertToLong() && valor.asLong() >= 0) {
            "$campo no es un número entero no negativo (${valor ?: "falta"})"
        }
        return valor.asLong()
    }
    val recibidos = cuenta("recibidos")
    val aplicados = cuenta("aplicados")
    val rechazados = cuenta("rechazados")
    val importe: JsonNode? = json["importe_aplicado"] ?: json["importeAplicado"]
    require(importe != null && !importe.isNull) { "falta importe_aplicado" }
    require(importe.isString) { "importe_aplicado llega como ${importe.nodeType} y no en cadena: un importe no viaja como número (regla 1)" }
    val texto = importe.asString()
    require(DECIMAL.matches(texto)) { "importe_aplicado no es un decimal sin signo escrito con punto: «$texto»" }
    require(BigDecimal(texto).scale() <= 2) { "importe_aplicado tiene más de 2 decimales: «$texto»; un importe no tiene milésimos" }
    // con la escala de un importe: «100» se lee 100.00, sin redondear nada (sumar el cero de dos decimales solo agrega
    // ceros), y la diferencia sale como las demás cifras
    return AplicadoEnElOrigen(recibidos, aplicados, rechazados, BigDecimal(texto).add(CERO))
}

// la petición: la conciliación EXIGE EL DÍA. sin él habría que elegir uno («hoy»), y una conciliación que se responde
// sola con la fecha del reloj no es reproducible al día siguiente (regla 6)
fun fechaDeLaConciliacion(valor: String?): LocalDate {
    val texto =
        valor?.trim()?.ifEmpty { null }
            ?: throw ValidationException("Falta la fecha", "fecha", "la conciliación es de un día: fecha=AAAA-MM-DD, obligatoria")
    return try {
        LocalDate.parse(texto)
    } catch (_: DateTimeParseException) {
        throw ValidationException("Fecha inválida", "fecha", "una fecha AAAA-MM-DD")
    }
}
