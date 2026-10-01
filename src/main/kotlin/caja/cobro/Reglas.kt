package caja.cobro

import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

// las reglas del alta de una orden de cobro, como funciones puras: el servicio las aplica, las pruebas las fijan.
// cada una devuelve el valor como se guarda o lanza un 400 sobre su campo (la clave snake_case del cuerpo)

// los largos de las columnas de caja (V2__ordenes_de_cobro_y_outbox.sql): wasichai guarda TEXT sin largo
const val LARGO_SISTEMA = 20
const val LARGO_REFERENCIA = 120
const val LARGO_CONCEPTO = 120
const val LARGO_DETALLE = 200
const val LARGO_DOCUMENTO = 20
const val LARGO_NOMBRE = 150

// numeric(15,2) de caja: 13 dígitos enteros y 2 decimales
const val ENTEROS_DEL_IMPORTE = 13

const val PENDIENTE = "PENDIENTE"
const val PAGADA = "PAGADA"
const val ANULADA = "ANULADA"
val ESTADOS = listOf(PENDIENTE, PAGADA, ANULADA)

private val SISTEMA = Regex("[a-z0-9_-]+")

// decimal llano: sin signo, sin exponente, sin punto suelto. [0-9] y no \d, que acepta dígitos de otros alfabetos
private val DECIMAL = Regex("[0-9]+(\\.[0-9]+)?")
private val ENTERO = Regex("[0-9]+")

// texto, no un enumerado: un sistema nuevo no es un despliegue de la caja. solo se comprueba la forma, porque es la
// mitad de la clave de idempotencia y dos formas de teclearlo serían dos sistemas
fun sistemaOrigen(valor: String?): String {
    val nombre = obligatorio(valor, "sistema_origen").lowercase(Locale.ROOT)
    if (nombre.length > LARGO_SISTEMA || !SISTEMA.matches(nombre)) {
        throw ValidationException(
            "Sistema de origen inválido",
            "sistema_origen",
            "en minúsculas, sin espacios ni acentos ([a-z0-9_-]), de 1 a $LARGO_SISTEMA caracteres"
        )
    }
    return nombre
}

// opaca: no se analiza, no se compara por partes y no se ordena. solo se recorta
fun referenciaExterna(valor: String?): String = texto(valor, "referencia_externa", LARGO_REFERENCIA)

// reemplaza orden_referencia_uq (sistema_origen, referencia_externa): wasichai no tiene unicidad compuesta. el sistema
// no lleva barra, así que la primera barra parte la clave sin ambigüedad
fun claveDeOrigen(
    sistema: String,
    referencia: String
): String = "$sistema|$referencia"

fun concepto(valor: String?): String = texto(valor, "concepto", LARGO_CONCEPTO)

fun detalle(valor: String?): String? = opcional(valor, "detalle", LARGO_DETALLE)

// regla 1: BigDecimal desde la cadena, nunca un número de coma flotante. mayor que 0 (una deuda en cero no se manda a
// la caja), con 2 decimales y 13 enteros a lo sumo (numeric(15,2) de caja). se guarda como vino: la caja no decide
// escala ni redondeo
fun importe(valor: String?): BigDecimal {
    val texto = obligatorio(valor, "importe")
    if (!DECIMAL.matches(texto)) {
        throw ValidationException("Importe inválido", "importe", "un decimal escrito con punto, como 10080.45")
    }
    val importe = BigDecimal(texto)
    if (importe.signum() <= 0) throw ValidationException("Importe inválido", "importe", "debe ser mayor que 0")
    if (importe.scale() > 2) throw ValidationException("Importe inválido", "importe", "a lo sumo 2 decimales")
    if (importe.precision() - importe.scale() > ENTEROS_DEL_IMPORTE) {
        throw ValidationException("Importe inválido", "importe", "a lo sumo $ENTEROS_DEL_IMPORTE dígitos enteros")
    }
    return importe
}

fun fecha(
    valor: String?,
    campo: String
): LocalDate {
    val texto = obligatorio(valor, campo)
    return try {
        LocalDate.parse(texto)
    } catch (_: DateTimeParseException) {
        throw ValidationException("Fecha inválida", campo, "una fecha AAAA-MM-DD")
    }
}

// quien paga, como la caja lo conoce. los tres opcionales: la caja guarda lo que le digan y no lo cruza con ningún
// padrón (el día que cobre un puesto de mercado, quien paga puede no estar en ninguno)
data class Pagador(
    val documento: String?,
    val nombre: String?,
    val idExterno: Long?
)

// el id externo llega en cadena (un número json, jackson lo pasa a cadena): se lee aquí, y lo que no es un entero
// mayor que 0 se rechaza sobre su campo. vacío es «no lo tiene», no «es el cero»
fun pagador(
    documento: String?,
    nombre: String?,
    idExterno: String?
): Pagador =
    Pagador(
        opcional(documento, "pagador_documento", LARGO_DOCUMENTO)?.uppercase(Locale.ROOT),
        opcional(nombre, "pagador_nombre", LARGO_NOMBRE),
        idExterno(idExterno)
    )

private fun idExterno(valor: String?): Long? {
    val texto = valor?.trim()?.ifEmpty { null } ?: return null
    val id = texto.takeIf(ENTERO::matches)?.toLongOrNull()
    if (id == null || id <= 0) {
        throw ValidationException("Pagador inválido", "pagador_externo_id", "un identificador entero mayor que 0, o ninguno")
    }
    return id
}

// el filtro de la ventanilla: sin estado, las pendientes
fun estadoOrden(valor: String?): String {
    val estado = valor?.trim()?.uppercase(Locale.ROOT)?.ifEmpty { null } ?: return PENDIENTE
    if (estado !in ESTADOS) throw ValidationException("Estado inválido", "estado", "uno de ${ESTADOS.joinToString(", ")}")
    return estado
}

// la frontera se defiende en la entrada: una orden no lleva tributo, ejercicio ni periodo, ni nada que la caja no
// conozca. callarlo dejaría creer al sistema de origen que la caja lo guardó
fun sinCamposDesconocidos(nombres: Collection<String>) {
    val nombre = nombres.firstOrNull() ?: return
    throw ValidationException("Campo desconocido", nombre, "una orden de cobro no lleva este campo")
}

private fun obligatorio(
    valor: String?,
    campo: String
): String = valor?.trim()?.ifEmpty { null } ?: throw ValidationException("Falta un dato", campo, "es obligatorio")

private fun texto(
    valor: String?,
    campo: String,
    largo: Int
): String = obligatorio(valor, campo).also { largoMaximo(it, campo, largo) }

private fun opcional(
    valor: String?,
    campo: String,
    largo: Int
): String? = valor?.trim()?.ifEmpty { null }?.also { largoMaximo(it, campo, largo) }

private fun largoMaximo(
    valor: String,
    campo: String,
    largo: Int
) {
    if (valor.length > largo) throw ValidationException("Dato demasiado largo", campo, "a lo sumo $largo caracteres")
}
