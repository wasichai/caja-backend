package caja.emision

import caja.cobro.Caja
import caja.cobro.LineaRecibo
import caja.cobro.Recibo
import caja.comun.ANULACION_RECIBO
import caja.comun.CAJA
import caja.comun.LIMA
import caja.comun.LINEA_RECIBO
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.Transaccion
import caja.turno.EstadoDelTurno
import caja.turno.LibroDelTurno
import org.springframework.stereotype.Service
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

// el original de un recibo: solo lo obtiene el cajero que lo emitió, el mismo día, con su turno abierto, y mientras no
// esté anulado. cualquier otra copia es un duplicado (POST /api/caja/recibos/{numero}/duplicados), que dice que lo es y
// dice si se anuló. con el turno cerrado, su arqueo está firmado y el original ya no se imprime. se lee como el usuario
// que llama: sin READ sobre recibo, 403 de core. todo se lee en UNA foto (Transaccion.lectura, como ConsultaDelTurno):
// la historia del turno son dos consultas, y un cierre y su reversión confirmados entre una y otra la dejarían rota (un
// 500). el pdf se dibuja fuera, sin la conexión tomada
@Service
class OriginalDelRecibo(
    private val registros: Registros,
    private val libro: LibroDelTurno,
    private val transaccion: Transaccion,
    private val currentUser: CurrentUser,
    private val pdf: ReciboPdf,
    private val reloj: Clock
) {
    suspend fun pdf(numeroImpreso: String): ByteArray {
        val usuario = currentUser.require()
        val numero = numeroImpreso.trim().uppercase(Locale.ROOT)
        val hoy = LocalDate.now(reloj)
        val (recibo, caja, lineas) =
            transaccion.lectura {
                val recibo =
                    registros.primero(RECIBO, Recibo::class.java, mapOf("numero_impreso" to numero))
                        ?: throw NotFoundException("No hay ningún recibo $numero")
                if (recibo.cajero != usuario.email || recibo.emitidoEn!!.atZone(LIMA).toLocalDate() != hoy) {
                    throw ConflictException(
                        "El original del recibo $numero solo lo imprime quien lo emitió, el mismo día y con su turno abierto: pida un duplicado"
                    )
                }
                if (libro.estado(recibo.turno!!) == EstadoDelTurno.CERRADO) {
                    throw ConflictException(
                        "El original del recibo $numero solo se imprime con su turno abierto, y el turno está cerrado (turno cerrado): pida un duplicado"
                    )
                }
                // un original sin la marca de su anulación circularía como un pago que ya no vale
                if (registros.count(ANULACION_RECIBO, mapOf("recibo" to recibo.id!!)) > 0) {
                    throw ConflictException("El recibo $numero está anulado: su original ya no se imprime, pida un duplicado, que lo dice")
                }
                val caja = registros.get(CAJA, Caja::class.java, UUID.fromString(recibo.caja))
                Triple(recibo, caja, registros.all(LINEA_RECIBO, LineaRecibo::class.java, filters = mapOf("recibo" to recibo.id)))
            }
        return pdf.original(recibo, caja, lineas)
    }
}
