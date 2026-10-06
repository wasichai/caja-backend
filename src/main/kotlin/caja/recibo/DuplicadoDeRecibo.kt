package caja.recibo

import caja.cobro.Caja
import caja.cobro.LineaRecibo
import caja.cobro.Recibo
import caja.cobro.campo
import caja.comun.ANULACION_RECIBO
import caja.comun.CAJA
import caja.comun.Candado
import caja.comun.Candados
import caja.comun.LINEA_RECIBO
import caja.comun.Observacion
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.REIMPRESION_RECIBO
import caja.comun.Registros
import caja.comun.Transaccion
import caja.emision.ReciboPdf
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

// el duplicado de un recibo ya emitido (DuplicadoDeRecibo de caja): el pdf marcado «DUPLICADO N.° n», y si el recibo
// se anuló, lo dice. no se recalcula nada: cada cifra sale de lo congelado en el recibo y sus líneas. cada reimpresión
// deja su reimpresion_recibo, y de contarlas sale el número del papel: un duplicado sin marca y sin rastro circula como
// si fuera el original.
//
// y no se afirma que sale igual: se comprueba. cada reimpresión guarda el SHA-256 de lo congelado (resumenDelRecibo);
// la siguiente lo vuelve a calcular y, si no coincide con el de alguna anterior, falla con 409 en vez de entregar un
// papel distinto con el mismo número.
//
// el pdf se dibuja DENTRO de la transacción, con el candado del recibo tomado, a propósito: la reimpresion_recibo queda
// si y solo si el papel se dibujó. dibujado después del commit, un fallo al dibujar dejaría contado un «DUPLICADO N.° n»
// que nadie recibió. se dibuja en Dispatchers.Default (PdfRenderer), no en el hilo de la petición
@Service
class DuplicadoDeRecibo(
    private val registros: Registros,
    private val candados: Candados,
    private val transaccion: Transaccion,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val pdf: ReciboPdf,
    private val reloj: Clock
) {
    // el pdf del duplicado y su número
    class Duplicado(
        val numeroImpreso: String,
        val cual: Int,
        val pdf: ByteArray
    )

    suspend fun imprimir(
        numeroImpreso: String,
        body: PeticionDeDuplicado
    ): Duplicado {
        val usuario = currentUser.require()
        // reimprimir es el privilegio IMPRESION de caja: crear una reimpresion_recibo
        permisos.exigir(usuario, "Reimprimir un recibo", "cada duplicado queda registrado", Actions.CREATE to REIMPRESION_RECIBO)
        val errores = mutableListOf<FieldViolation>()
        val numero = campo(errores) { numeroDeRecibo(numeroImpreso) }
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "un duplicado") }
        val observacion = campo(errores) { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("El duplicado no es válido", errores)

        return transaccion.en {
            val recibo =
                registros.primero(RECIBO, Recibo::class.java, mapOf("numero_impreso" to numero!!))
                    ?: throw NotFoundException("No hay ningún recibo $numero")
            val reciboId = recibo.id!!
            // bajo el candado del recibo: dos duplicados a la vez no salen con el mismo número
            candados.bloquear(Candado.RECIBO, reciboId)

            val lineas = registros.all(LINEA_RECIBO, LineaRecibo::class.java, filters = mapOf("recibo" to reciboId))
            val resumen = resumenDelRecibo(recibo, lineas)
            val anteriores = registros.all(REIMPRESION_RECIBO, ReimpresionRecibo::class.java, filters = mapOf("recibo" to reciboId))
            anteriores.firstOrNull { it.resumen != resumen }?.let { distinta ->
                throw ConflictException(
                    "El recibo $numero ya no se dibuja igual que en su reimpresión del ${distinta.fecha}: el resumen era " +
                        "${distinta.resumen!!.take(12)}… y ahora es ${resumen.take(12)}…. Entregarlo sería dar un papel distinto al original " +
                        "con el mismo número"
                )
            }
            val cual = anteriores.size + 1
            val anulacion = registros.primero(ANULACION_RECIBO, AnulacionRecibo::class.java, mapOf("recibo" to reciboId))
            val caja = registros.get(CAJA, Caja::class.java, UUID.fromString(recibo.caja))
            val documento = pdf.duplicado(recibo, caja, lineas, cual, anulacion?.let { ReciboPdf.Anulado(it.fecha!!, it.motivo!!) })

            registros.create(
                REIMPRESION_RECIBO,
                ReimpresionRecibo::class.java,
                mapOf(
                    "recibo" to reciboId,
                    "fecha" to LocalDate.now(reloj).toString(),
                    "resumen" to resumen,
                    "usuario" to usuario.email,
                    "observacion" to observacion!!.texto
                )
            )
            Duplicado(numero, cual, documento)
        }
    }
}
