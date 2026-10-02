package caja.turno

import caja.cobro.Caja
import caja.cobro.Turno
import caja.comun.CAJA
import caja.comun.Importe
import caja.comun.LIMA
import caja.comun.Registros
import caja.comun.TURNO
import caja.comun.Transaccion
import org.springframework.stereotype.Service
import wasichai.core.common.NotFoundException
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

// las dos lecturas del turno, sin candados ni escritura (ConsultaDelTurno, TurnoController y EstadoDelCierreController
// de caja). cada una lee en una sola foto (Transaccion.lectura, REPEATABLE READ): el arqueo y sus pagos no se descuadran
// si un cobro se confirma entre una lectura y otra. se lee como el usuario que llama
@Service
class ConsultaDelTurno(
    private val registros: Registros,
    private val libro: LibroDelTurno,
    private val transaccion: Transaccion,
    private val currentUser: CurrentUser,
    private val reloj: Clock
) {
    // los turnos de HOY de quien pregunta, en todas sus ventanillas, con su situación. el cajero es el de la sesión y el
    // día, el del reloj: ningún parámetro. y PREGUNTAR NO ABRE UN TURNO: lo abre el primer cobro, que es un acto con su
    // observación; una lectura que abriera turnos los llenaría de cajeros que solo miraron la pantalla
    suspend fun delDia(parametros: Collection<String>): TurnoDelDia {
        sinParametros(parametros)
        val cajero = currentUser.require().email
        val hoy = LocalDate.now(reloj)
        return transaccion.lectura {
            val turnos = registros.all(TURNO, Turno::class.java, filters = mapOf("cajero" to cajero, "fecha" to hoy.toString()))
            val cajas = registros.byIds(CAJA, Caja::class.java, turnos.map { it.caja!! })
            val historias = libro.historias(turnos.map { it.id!! })
            val filas =
                turnos
                    .map { turno ->
                        val caja = cajas[turno.caja]
                        TurnoEnElDia(
                            turnoId = turno.id!!,
                            caja = caja?.codigo,
                            cajaNombre = caja?.nombre,
                            cajero = turno.cajero!!,
                            fecha = turno.fecha.toString(),
                            abiertoEn =
                                turno.abiertoEn!!
                                    .atZone(LIMA)
                                    .toOffsetDateTime()
                                    .toString(),
                            estadoDelTurno = EstadoDelTurno.de(historias.getValue(turno.id))
                        )
                    }.sortedWith(compareBy({ it.caja }, { it.turnoId }))
            TurnoDelDia(cajero, hoy.toString(), SituacionDelCajero.de(filas.map { it.estadoDelTurno }), filas)
        }
    }

    // el arqueo EN VIVO de un turno: lo que hay, sin lo declarado (un GET no lleva el recuento del cajón, así que
    // declarado y diferencia van en null, nunca en cero), las dos mitades del cuadre y lo que impide cerrar. las cifras,
    // a hoy
    suspend fun arqueo(turnoId: String): ArqueoDelTurnoRespuesta {
        val id = turnoIdPedido(turnoId)
        currentUser.require()
        val hoy = LocalDate.now(reloj)
        return transaccion.lectura {
            try {
                registros.get(TURNO, Turno::class.java, UUID.fromString(id))
            } catch (_: NotFoundException) {
                throw NotFoundException("No hay ningún turno $id")
            }
            val historia = libro.historia(id)
            val estado = EstadoDelTurno.de(historia)
            val recibos = libro.recibos(id)
            val cuadre = Cuadre.de(recibos.contables)
            val pagos = libro.pagosSinEntregar(id)
            // cerrado, el acta de su cierre vigente tal como se firmó; abierto (o reversado), ninguna
            val vigente =
                cierreVigente(historia)?.let { movimiento ->
                    val (acta, lineas) = libro.acta(movimiento.id)
                    CierreVigente.de(
                        acta,
                        lineas,
                        acta.registradoEn!!
                            .atZone(LIMA)
                            .toOffsetDateTime()
                            .toString()
                    )
                }
            ArqueoDelTurnoRespuesta(
                turnoId = id,
                estadoDelTurno = estado,
                puedeCerrar = estado == EstadoDelTurno.ABIERTO && pagos.isEmpty(),
                arqueo = ArqueoRespuesta.enVivo(ArqueoDelTurno.de(recibos.contables, emptyMap(), hoy)),
                cobradoConEvento = Importe.de(cuadre.conEvento, hoy),
                cobradoSinEvento = Importe.de(cuadre.sinEvento, hoy),
                loQueImpideCerrar = pagos,
                cierreVigente = vigente,
                recibosConDatosRotos = ReciboRotoRespuesta.de(recibos.rotos)
            )
        }
    }
}
