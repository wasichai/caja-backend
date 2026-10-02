package caja.buzon

import caja.cobro.PagoEvento
import caja.cobro.Recibo
import caja.comun.PAGO_EVENTO
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.Transaccion
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.identity.CurrentUser

// los pagos que ningún sistema de origen pudo registrar (PagoController de caja, la pantalla del responsable de la
// conciliación: lo que la alerta le dice que mire). se leen como el usuario que llama, en una sola foto
@Service
class PagosSinEntregar(
    private val registros: Registros,
    private val permisos: Permisos,
    private val transaccion: Transaccion,
    private val currentUser: CurrentUser
) {
    // los MUERTO, del más antiguo al más reciente, con el número impreso de su recibo
    suspend fun listar(): List<PagoDelBuzon> {
        val usuario = currentUser.require()
        permisos.exigir(
            usuario,
            "Ver los pagos sin entregar",
            "la lista dice qué pagos y de qué recibos",
            Actions.READ to PAGO_EVENTO,
            Actions.READ to RECIBO
        )
        return transaccion.lectura {
            val muertos = registros.all(PAGO_EVENTO, PagoEvento::class.java, filters = mapOf("estado" to BuzonStore.MUERTO), sort = "created_at")
            val recibos = registros.byIds(RECIBO, Recibo::class.java, muertos.mapNotNull { it.recibo })
            muertos.map { pagoDelBuzon(it, recibos[it.recibo]?.numeroImpreso) }
        }
    }
}
