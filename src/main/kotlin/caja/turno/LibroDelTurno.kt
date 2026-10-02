package caja.turno

import caja.cobro.PagoEvento
import caja.cobro.Recibo
import caja.cobro.Turno
import caja.comun.ANULACION_RECIBO
import caja.comun.CIERRE_TURNO
import caja.comun.CIERRE_TURNO_LINEA
import caja.comun.PAGO_EVENTO
import caja.comun.RECIBO
import caja.comun.REVERSION_CIERRE
import caja.comun.Registros
import caja.recibo.AnulacionRecibo
import org.springframework.stereotype.Component
import wasichai.core.common.ConflictException
import java.math.BigDecimal
import java.util.UUID

// lo que la base sabe de un turno, leído como el usuario que llama (sus permisos son los de core): su historia de
// cierres y reversiones, sus recibos con lo que devolvió su anulación y sus pagos sin entregar. no decide nada: las
// reglas son ArqueoDelTurno y CierreDeTurno, puras. quien necesita que lo leído siga valiendo (el cobro, la anulación,
// el cierre) lo lee DESPUÉS de tomar el candado del turno
@Component
class LibroDelTurno(
    private val registros: Registros
) {
    // los movimientos del turno: sus cierres y sus reversiones, en dos objetos que comparten la secuencia
    suspend fun historia(turnoId: String): List<Movimiento> = historias(listOf(turnoId)).getValue(turnoId)

    // la historia de cada turno, con dos consultas para todos
    suspend fun historias(turnoIds: Collection<String>): Map<String, List<Movimiento>> {
        if (turnoIds.isEmpty()) return emptyMap()
        val cierres =
            registros.byRelation(CIERRE_TURNO, CierreTurno::class.java, "turno", turnoIds).map {
                it.turno!! to Movimiento(it.id!!, TipoDeMovimiento.CIERRE, it.secuencia!!)
            }
        val reversiones =
            registros.byRelation(REVERSION_CIERRE, ReversionCierre::class.java, "turno", turnoIds).map {
                it.turno!! to Movimiento(it.id!!, TipoDeMovimiento.REVERSION, it.secuencia!!)
            }
        val porTurno = (cierres + reversiones).groupBy({ it.first }, { it.second })
        return turnoIds.associateWith { porTurno[it].orEmpty() }
    }

    suspend fun estado(turnoId: String): EstadoDelTurno = EstadoDelTurno.de(historia(turnoId))

    // el acta de un cierre y sus líneas, tal como se guardaron
    suspend fun acta(cierreId: String): Pair<CierreTurno, List<CierreTurnoLinea>> =
        registros.get(CIERRE_TURNO, CierreTurno::class.java, UUID.fromString(cierreId)) to
            registros.byRelation(CIERRE_TURNO_LINEA, CierreTurnoLinea::class.java, "cierre_turno", listOf(cierreId))

    // 409 «turno cerrado» si lo está (TurnoCerrado y AnularRecibo.TurnoYaCerrado de caja): su arqueo está firmado, y lo
    // que entrara o saliera ahora no estaría en él. que: lo que no se puede hacer
    suspend fun exigirAbierto(
        turno: Turno,
        que: String
    ) {
        if (estado(turno.id!!) == EstadoDelTurno.CERRADO) {
            throw ConflictException(
                "Turno cerrado: el turno de ${turno.cajero} del ${turno.fecha} está cerrado y su arqueo, firmado, así que $que. " +
                    "Un cierre no se modifica: para seguir ese día hay que reversar el cierre, que reabre el turno"
            )
        }
    }

    // los recibos del turno, vistos desde el arqueo: cada uno con lo que su anulación congeló, o cero, partidos en los
    // que el arqueo cuenta y los rotos con su porqué (RecibosDelTurno): uno roto no tumba el arqueo con un 500
    suspend fun recibos(turnoId: String): RecibosDelTurno {
        val recibos = registros.all(RECIBO, Recibo::class.java, filters = mapOf("turno" to turnoId))
        val anulado =
            registros
                .byRelation(ANULACION_RECIBO, AnulacionRecibo::class.java, "recibo", recibos.map { it.id!! })
                .associate { it.recibo!! to it.importe!! }
        return RecibosDelTurno.de(
            recibos.map { FilaDeRecibo(it.numeroImpreso!!, it.tipoPago!!, it.formaPago!!, it.total!!, anulado[it.id] ?: BigDecimal.ZERO) }
        )
    }

    // los pagos del turno que su sistema de origen todavía no conoce: PENDIENTE o MUERTO. impiden cerrar
    suspend fun pagosSinEntregar(turnoId: String): List<PagoSinEntregar> =
        registros
            .all(PAGO_EVENTO, PagoEvento::class.java, filters = mapOf("turno" to turnoId))
            .filter { it.estado in PAGOS_SIN_ENTREGAR }
            .map { PagoSinEntregar(it.eventoId!!, it.tipo!!, it.estado!!) }
            .sortedBy { it.pagoId }
}
