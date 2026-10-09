package caja.cobro

import caja.comun.campo
import caja.modelo.LineaRecibo
import caja.modelo.Tasa
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

// las reglas puras de la caja de tasas (CobrarTasa de caja). el precio sale de la tasa vigente a la fecha del cobro,
// nunca de la petición ni de una constante (regla 5): que viniera de la petición dejaría al cliente poner la tarifa, y
// una compilada solo se cambiaría desplegando. aquí no hay ninguna cifra: todas son datos de la tabla tasa

// lo que la ventanilla ve del pago de un recibo sin evento
const val SIN_EVENTO = "SIN_EVENTO"

// las claves que traerían una cifra: un cobro de tasas no lleva ninguna
private val CIFRAS = setOf("importe", "precio", "precio_unitario", "monto", "total")

// la tarifa vigente a la fecha: la que ya empezó y no terminó. si dos vigencias se solaparan por error, la que empezó
// después (ORDER BY vigencia_desde DESC LIMIT 1 de TasaRepositoryJdbc): fallar con «más de una» sería un error
// incomprensible en ventanilla
fun tarifaVigente(
    tasas: List<Tasa>,
    fecha: LocalDate
): Tasa? = tasas.filter { it.vigenteA(fecha) }.maxByOrNull { it.vigenciaDesde!! }

// precio × cantidad, sin redondear: multiplicar por un entero no agrega decimales (recibo_detalle_tasa_ck de caja)
fun montoDeLinea(
    precio: BigDecimal,
    cantidad: Int
): BigDecimal {
    require(cantidad >= 1) { "La cantidad de un concepto es al menos 1; llegó $cantidad" }
    return precio.multiply(BigDecimal.valueOf(cantidad.toLong()))
}

// lo que el cajero marcó: qué concepto y cuántas veces. el precio no viaja aquí (LineaDeTasaPedida de caja)
data class LineaDeTasaPedida(
    val codigo: String,
    val cantidad: Int
)

// un concepto con su tarifa vigente
class TasaCotizada(
    val tasa: Tasa,
    val cantidad: Int
) {
    val monto: BigDecimal get() = montoDeLinea(tasa.importe!!, cantidad)
}

// los conceptos cotizados y lo que impide cobrar los demás, en el orden de la petición
class Cotizacion(
    val lineas: List<TasaCotizada>,
    val impedimentos: List<WasichaiException>
)

// cada concepto con la tarifa vigente a la fecha, o lo que impide cobrarlo: sin tarifa vigente, 404 con su código;
// una tarifa en cero o una vigencia al revés, 409 (son datos mal cargados, y un recibo por cero no documenta un cobro).
// el cobro lanza el primer impedimento; la vista previa los dice todos, y un concepto mal cargado no tumba a los demás.
// tasasPorCodigo trae todas las vigencias de cada código pedido
fun cotizar(
    pedidas: List<LineaDeTasaPedida>,
    tasasPorCodigo: Map<String, List<Tasa>>,
    fecha: LocalDate
): Cotizacion {
    val lineas = mutableListOf<TasaCotizada>()
    val impedimentos = mutableListOf<WasichaiException>()
    pedidas.forEach { pedida ->
        val tasa =
            try {
                tarifaVigente(tasasPorCodigo[pedida.codigo].orEmpty(), fecha)
            } catch (malCargada: ConflictException) {
                impedimentos += malCargada
                return@forEach
            }
        when {
            tasa == null ->
                impedimentos +=
                    NotFoundException(
                        "El concepto '${pedida.codigo}' (conceptos) no tiene tarifa vigente al $fecha: la tarifa es un dato registrado " +
                            "con su norma y su vigencia, y sin una vigente no hay nada que cobrar"
                    )
            tasa.importe!!.signum() <= 0 ->
                impedimentos +=
                    ConflictException(
                        "La tarifa vigente del concepto '${pedida.codigo}' es ${tasa.importe.toPlainString()}: tarifa en cero, un dato mal " +
                            "cargado. Un recibo por cero no documenta un cobro; corrija la tarifa (desde ${tasa.vigenciaDesde})"
                    )
            else -> lineas += TasaCotizada(tasa, pedida.cantidad)
        }
    }
    return Cotizacion(lineas, impedimentos)
}

// la línea del recibo de un concepto: la tasa, su descripción, la cantidad, el precio vigente y el monto. sin detalle,
// sin sistema y sin referencia: eso lo pone un sistema de origen, y una tasa no tiene ninguno
fun lineaDeTasa(cotizada: TasaCotizada): LineaRecibo =
    LineaRecibo(
        tasa = cotizada.tasa.id,
        concepto = cotizada.tasa.descripcion,
        cantidad = cotizada.cantidad.toLong(),
        precioUnitario = cotizada.tasa.importe,
        monto = cotizada.monto
    )

// lo que llega en la petición

// al menos un concepto, cada uno con su código y su cantidad (1 si no viene). el código se recorta y va en mayúsculas,
// como en caja y como lo guarda import_tasas.py. todo lo que falla en todos los conceptos, en un solo 400
fun conceptosPedidos(valores: List<ConceptoPedido>?): List<LineaDeTasaPedida> {
    if (valores.isNullOrEmpty()) {
        throw ValidationException("Faltan los conceptos", "conceptos", "al menos uno: un recibo sin líneas no documenta nada")
    }
    val errores = mutableListOf<FieldViolation>()
    val pedidas =
        valores.mapIndexed { i, concepto ->
            val codigo =
                campo(errores) {
                    concepto.codigo
                        ?.trim()
                        ?.uppercase(Locale.ROOT)
                        ?.ifEmpty { null }
                        ?: throw ValidationException("Falta un dato", "conceptos[$i].codigo", "el código de la tasa")
                }
            val cantidad = campo(errores) { cantidadPedida(concepto.cantidad, "conceptos[$i].cantidad") }
            campo(errores) { sinPrecioNiCamposDesconocidos(concepto.desconocidos, "conceptos[$i].") }
            codigo?.let { c -> cantidad?.let { LineaDeTasaPedida(c, it) } }
        }
    if (errores.isNotEmpty()) throw ValidationException("Los conceptos no son válidos", errores)
    return pedidas.map { it!! }
}

// cuántas veces: un entero de al menos 1, y 1 si no viene
fun cantidadPedida(
    valor: String?,
    campo: String
): Int {
    val texto = valor?.trim()?.ifEmpty { null } ?: return 1
    val cantidad = texto.takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()
    if (cantidad == null || cantidad < 1) throw ValidationException("Cantidad inválida", campo, "un entero de al menos 1")
    return cantidad
}

// el cuerpo de un cobro de tasas no lleva cifras: un importe o un precio es un 400 que lo nombra, y cualquier otra clave
// desconocida también. todas en el mismo 400
fun sinPrecioNiCamposDesconocidos(
    nombres: Collection<String>,
    prefijo: String = ""
) {
    if (nombres.isEmpty()) return
    val violaciones =
        nombres.map { nombre ->
            if (nombre in CIFRAS) {
                FieldViolation("$prefijo$nombre", "el precio sale de la tarifa vigente a la fecha del cobro, nunca de la petición: quite este campo")
            } else {
                FieldViolation("$prefijo$nombre", "un cobro de tasas no lleva este campo")
            }
        }
    val titulo = if (nombres.any { it in CIFRAS }) "El precio no viaja en la petición" else "Campo desconocido"
    throw ValidationException(titulo, violaciones)
}
