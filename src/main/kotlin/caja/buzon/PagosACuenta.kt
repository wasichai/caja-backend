package caja.buzon

import caja.comun.PAGO_EVENTO
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.TURNO
import caja.comun.Transaccion
import caja.modelo.PagoEvento
import caja.modelo.Recibo
import caja.modelo.Turno
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.identity.CurrentUser

// los pagos que el sistema de origen recibió «a cuenta» un día (caja-backend#41): se entregaron, pero la deuda ya no
// estaba vigente y el origen los guardó como saldo a favor del pagador. no bloquean nada (un ENTREGADO no impide cerrar),
// y por eso mismo se listan: la hoja de cierre los muestra aparte, para que alguien vea el dinero que no extinguió una
// deuda. el día es el de los TURNOS, como la conciliación, y se lee como el usuario que llama, en una sola foto
@Service
class PagosACuenta(
    private val registros: Registros,
    private val permisos: Permisos,
    private val transaccion: Transaccion,
    private val currentUser: CurrentUser
) {
    suspend fun del(fecha: String?): List<PagoACuenta> {
        val usuario = currentUser.require()
        permisos.exigir(
            usuario,
            "Ver los pagos recibidos a cuenta",
            "la lista dice qué pagos y de qué recibos",
            Actions.READ to PAGO_EVENTO,
            Actions.READ to RECIBO
        )
        val dia = fechaDeLosPagosACuenta(fecha)
        return transaccion.lectura {
            val turnos = registros.all(TURNO, Turno::class.java, filters = mapOf("fecha" to dia.toString()))
            val aCuenta =
                registros
                    .byRelation(PAGO_EVENTO, PagoEvento::class.java, "turno", turnos.map { it.id!! })
                    .filter { it.aCuenta == true }
                    .sortedWith(compareBy({ it.entregadoEn }, { it.eventoId }))
            val recibos = registros.byIds(RECIBO, Recibo::class.java, aCuenta.mapNotNull { it.recibo })
            aCuenta.map { pagoACuenta(it, recibos[it.recibo], dia) }
        }
    }
}
