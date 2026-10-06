package caja.buzon

import caja.cobro.PagoEvento
import caja.cobro.Recibo
import caja.cobro.campo
import caja.comun.Candado
import caja.comun.Candados
import caja.comun.Observacion
import caja.comun.PAGO_EVENTO
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.Transaccion
import caja.recibo.sinCamposDesconocidos
import org.springframework.stereotype.Service
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import java.util.UUID

// alguien se hace cargo, por escrito, de un pago que no se pudo entregar (ExplicarPagoSinEntregar de caja, ADR-0026 §4).
// el cierre del turno es bloqueante: sin esta salida, un evento que de verdad no se puede entregar dejaría esa caja sin
// cerrar para siempre, y la presión acabaría relajando el cierre para todos. así que la salida existe y cuesta lo que
// tiene que costar: SOLO UN PAGO MUERTO (uno PENDIENTE se entregaría solo, y explicarlo lo sacaría de la cola), con su
// explicación en el evento y su observación en la auditoría, y SOLO con UPDATE sobre pago_evento, que roles.json da
// únicamente a SUPERVISOR_CAJA: es la única edición del buzón que hace una persona. se escribe por RecordService, como el
// usuario, con el candado del evento (el mismo que toma cada marca del publicador, BuzonStore) y releyéndolo bajo él:
// dos explicaciones a la vez dan una y un 409, y una explicación y una marca del publicador no se pisan
@Service
class ExplicarPagoSinEntregar(
    private val registros: Registros,
    private val candados: Candados,
    private val transaccion: Transaccion,
    private val permisos: Permisos,
    private val auditoria: AuditService,
    private val currentUser: CurrentUser
) {
    suspend fun explicar(
        pagoId: String,
        body: PeticionDeExplicacion
    ): PagoDelBuzon {
        val usuario = currentUser.require()
        permisos.exigir(
            usuario,
            "Explicar un pago sin entregar",
            "lo pasa de MUERTO a EXPLICADO, y eso deja cerrar su turno",
            Actions.UPDATE to PAGO_EVENTO,
            Actions.READ to RECIBO
        )
        val id = pagoIdPedido(pagoId)
        val errores = mutableListOf<FieldViolation>()
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "una explicación") }
        val explicacion = campo(errores) { explicacionDe(body.explicacion) }
        val observacion = campo(errores) { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("La explicación no es válida", errores)

        return transaccion.en {
            val leido =
                registros.primero(PAGO_EVENTO, PagoEvento::class.java, mapOf("evento_id" to id))
                    ?: throw NotFoundException("No hay ningún pago $id")
            candados.bloquear(Candado.PAGO, id)
            // bajo el candado, lo que hay ahora
            val actual = registros.get(PAGO_EVENTO, PagoEvento::class.java, UUID.fromString(leido.id))
            if (actual.estado != BuzonStore.MUERTO) {
                throw ConflictException(
                    "Solo se explica un pago MUERTO, y el $id está ${actual.estado}: " +
                        when (actual.estado) {
                            BuzonStore.PENDIENTE -> "todavía se está intentando entregar, y explicarlo lo sacaría de la cola"
                            BuzonStore.ENTREGADO -> "su sistema de origen ya lo tiene"
                            else -> "alguien ya se hizo cargo de él"
                        }
                )
            }
            val explicado =
                registros.replace(PAGO_EVENTO, PagoEvento::class.java, UUID.fromString(actual.id), mapOf("estado" to EXPLICADO, "explicacion" to explicacion))
            // el por qué del acto (regla 10): pago_evento no tiene observación, va en la auditoría, junto a la de core
            auditoria.record(
                usuario.organizationId,
                usuario.userId,
                PAGO_EVENTO,
                UUID.fromString(actual.id),
                AuditOperation.UPDATE,
                before = mapOf("estado" to actual.estado),
                after = mapOf("estado" to EXPLICADO, "explicacion" to explicacion, "observacion" to observacion!!.texto)
            )
            val numero = explicado.recibo?.let { registros.byIds(RECIBO, Recibo::class.java, listOf(it))[it]?.numeroImpreso }
            pagoDelBuzon(explicado, numero)
        }
    }
}
