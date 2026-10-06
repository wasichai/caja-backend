package caja.recibo

import caja.cobro.EVENTO_PENDIENTE
import caja.cobro.LineaRecibo
import caja.cobro.OrdenDeCobro
import caja.cobro.PAGADA
import caja.cobro.PAGO_REGISTRADO
import caja.cobro.PENDIENTE
import caja.cobro.PagoEvento
import caja.cobro.Recibo
import caja.cobro.Turno
import caja.cobro.produceEvento
import caja.comun.ANULACION_RECIBO
import caja.comun.Candado
import caja.comun.Candados
import caja.comun.Importe
import caja.comun.LINEA_RECIBO
import caja.comun.ORDEN_DE_COBRO
import caja.comun.Observacion
import caja.comun.PAGO_EVENTO
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.TURNO
import caja.comun.Transaccion
import caja.comun.campo
import caja.comun.sinCamposDesconocidos
import caja.turno.LibroDelTurno
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

// anula un recibo el mismo día del pago (AnularRecibo de caja, RF-083). EL RECIBO NO SE TOCA: anular es agregar una
// anulacion_recibo, y el número, las líneas y el total siguen donde estaban, porque el pagador tiene ese papel en la
// mano. todo en UNA transacción, bajo el candado del turno del recibo (el mismo que toman el cobro y el cierre) y
// después los de sus órdenes, por id: el orden de siempre, turno → órdenes. cada decisión se toma con lo leído después
// de tomar su candado; el unique de recibo_anulado es la red, y si salta no se relee nada dentro.
//
// las órdenes vuelven a PENDIENTE y sin recibo, no ANULADA: el dinero volvió y la deuda sigue, así que tienen que poder
// cobrarse otra vez. y si el recibo avisó su pago (NORMAL), sale PAGO_ANULADO en el buzón, en la misma transacción
@Service
class AnularRecibo(
    private val registros: Registros,
    private val candados: Candados,
    private val transaccion: Transaccion,
    private val libro: LibroDelTurno,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val reloj: Clock
) {
    suspend fun anular(
        numeroImpreso: String,
        body: PeticionDeAnulacion
    ): AnulacionRespuesta {
        val usuario = currentUser.require()
        // anular es el privilegio ELIMINACION de caja: crear una anulacion_recibo. lo demás (devolver las órdenes,
        // encolar el evento) lo aplica core al escribir
        permisos.exigir(usuario, "Anular un recibo", "la anulación se agrega y deja el acta", Actions.CREATE to ANULACION_RECIBO)
        val pedido = pedido(numeroImpreso, body)
        return try {
            transaccion.en { anularEnLaTransaccion(pedido, usuario) }
        } catch (choque: DuplicateKeyException) {
            // la red del unique de recibo_anulado (o de evento_id): otra anulación se confirmó a la vez. la transacción
            // ya se revirtió entera
            throw ConflictException("El recibo ${pedido.numero} se anuló a la vez desde otra petición: ya está anulado")
                .apply { initCause(choque) }
        }
    }

    private suspend fun anularEnLaTransaccion(
        pedido: Pedido,
        usuario: AuthenticatedUser
    ): AnulacionRespuesta {
        val numero = pedido.numero
        // el recibo no cambia nunca: se lee antes de su candado
        val recibo =
            registros.primero(RECIBO, Recibo::class.java, mapOf("numero_impreso" to numero))
                ?: throw NotFoundException("No hay ningún recibo $numero")
        val reciboId = recibo.id!!

        // 1. el candado del turno del recibo, el mismo de la cobranza: un cierre en curso no se cruza con esta anulación
        candados.bloquear(Candado.TURNO, recibo.turno!!)
        val turno = registros.get(TURNO, Turno::class.java, UUID.fromString(recibo.turno))

        // 2. solo el mismo día: la fecha del turno contra el que se cobró, no la del reloj de la petición (422)
        val hoy = LocalDate.now(reloj)
        delMismoDia(numero, turno.fecha!!, hoy)
        // el turno ya cerrado (TurnoYaCerrado de caja, 409), bajo su candado y con su historia leída después: su arqueo
        // congeló este recibo como cobrado, y anularlo ahora lo desmentiría. una anulación que esperaba a un cierre en
        // curso lo encuentra cerrado
        libro.exigirAbierto(turno, "no se anula ninguno de sus recibos: el acta ya congeló el $numero como cobrado")

        // 3. anular dos veces no anula dos veces: 409. el unique de recibo_anulado es la red
        registros.primero(ANULACION_RECIBO, AnulacionRecibo::class.java, mapOf("recibo" to reciboId))?.let {
            throw ConflictException("El recibo $numero ya se anuló el ${it.fecha}: las órdenes que cobró ya volvieron a PENDIENTE")
        }

        // 4. ESPECIAL: el recibo de otro cajero exige SUPERVISOR_CAJA (403)
        puedeAnular(recibo.cajero!!, usuario, numero)

        // no se anula lo que no avisó: un recibo NORMAL sin su PAGO_REGISTRADO pediría al origen deshacer un pago que no
        // conoce. un recibo de tasas no avisa a nadie
        val registrado =
            if (produceEvento(recibo.tipoPago!!)) {
                registros.primero(PAGO_EVENTO, PagoEvento::class.java, mapOf("recibo" to reciboId, "tipo" to PAGO_REGISTRADO))
                    ?: throw ConflictException(
                        "El recibo $numero no tiene su evento PAGO_REGISTRADO en el buzón: no hay pago que reversar, y mandar una " +
                            "anulación de un pago que el sistema de origen no conoce le pediría deshacer algo que no hizo"
                    )
            } else {
                null
            }

        // 5. el acta, con el total del recibo congelado. la caja y el turno son los del recibo: el arqueo de ese turno
        // resta lo anulado
        val anulacion =
            registros.create(
                ANULACION_RECIBO,
                AnulacionRecibo::class.java,
                mapOf(
                    "recibo" to reciboId,
                    "recibo_anulado" to reciboId,
                    "caja" to recibo.caja,
                    "turno" to recibo.turno,
                    "fecha" to hoy.toString(),
                    "motivo" to pedido.motivo,
                    "autorizado_por" to pedido.autorizadoPor,
                    "documento_autorizacion" to pedido.documentoAutorizacion,
                    "importe" to recibo.total!!.toPlainString(),
                    "usuario" to usuario.email,
                    "observacion" to pedido.observacion.texto
                )
            )

        // 6. las órdenes vuelven a PENDIENTE y sin recibo
        devolverAPendiente(recibo)

        // 7. PAGO_ANULADO, con el pagoId del PAGO_REGISTRADO que deshace
        val pagoAnuladoId =
            registrado?.let { original ->
                val pagoId = UUID.randomUUID()
                registros.create(
                    PAGO_EVENTO,
                    PagoEvento::class.java,
                    mapOf(
                        "evento_id" to pagoId.toString(),
                        "tipo" to PAGO_ANULADO,
                        "sistema_destino" to original.sistemaDestino,
                        "recibo" to reciboId,
                        "turno" to recibo.turno,
                        "cuerpo" to cuerpoPagoAnulado(pagoId, original.eventoId!!, recibo, pedido.motivo, hoy),
                        "estado" to EVENTO_PENDIENTE,
                        "intentos" to 0
                    )
                )
                pagoId.toString()
            }

        // 8. el recibo no se modifica
        return AnulacionRespuesta(
            numeroImpreso = numero,
            estado = ANULADO,
            fecha = anulacion.fecha.toString(),
            motivo = anulacion.motivo!!,
            autorizadoPor = anulacion.autorizadoPor,
            documentoAutorizacion = anulacion.documentoAutorizacion,
            usuario = anulacion.usuario!!,
            importe = Importe.de(recibo.total, recibo.actualizadoA!!),
            pagoAnuladoId = pagoAnuladoId
        )
    }

    // las órdenes que cobró el recibo (sus líneas no cambian: se leen sin candado), cada una bajo su candado, en orden
    // de id, y releídas después de tomarlos. replace lee, mezcla y escribe sin control de versión: va bajo el candado
    private suspend fun devolverAPendiente(recibo: Recibo) {
        val reciboId = recibo.id!!
        val ids =
            registros
                .all(LINEA_RECIBO, LineaRecibo::class.java, filters = mapOf("recibo" to reciboId))
                .mapNotNull { it.orden }
                .distinct()
                .sorted()
        ids.forEach { candados.bloquear(Candado.ORDEN, it) }
        val ordenes = registros.byIds(ORDEN_DE_COBRO, OrdenDeCobro::class.java, ids)
        ids.forEach { id ->
            val orden = ordenes[id]
            // orden_recibo_ck: una orden de un recibo vigente está PAGADA y lo nombra. si no, el dato está roto
            check(orden != null && orden.estado == PAGADA && orden.recibo == reciboId) {
                "La orden $id del recibo ${recibo.numeroImpreso} no está PAGADA con ese recibo: ${orden?.estado} con ${orden?.recibo}"
            }
            registros.replace(ORDEN_DE_COBRO, OrdenDeCobro::class.java, UUID.fromString(id), mapOf("estado" to PENDIENTE, "recibo" to null))
        }
    }

    // las reglas sobre la petición, con todos los campos que fallan en un solo 400
    private fun pedido(
        numeroImpreso: String,
        body: PeticionDeAnulacion
    ): Pedido {
        val errores = mutableListOf<FieldViolation>()
        val numero = campo(errores) { numeroDeRecibo(numeroImpreso) }
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "una anulación") }
        val motivo = campo(errores) { motivoDeAnulacion(body.motivo) }
        val autorizado = campo(errores) { autorizadoPor(body.autorizadoPor) }
        val documento = campo(errores) { documentoDeAutorizacion(body.documentoAutorizacion) }
        val observacion = campo(errores) { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("La anulación no es válida", errores)
        return Pedido(numero!!, motivo!!, autorizado, documento, observacion!!)
    }

    private class Pedido(
        val numero: String,
        val motivo: String,
        val autorizadoPor: String?,
        val documentoAutorizacion: String?,
        val observacion: Observacion
    )
}
