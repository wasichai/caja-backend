package caja.emision

import caja.cobro.Caja
import caja.cobro.LineaRecibo
import caja.cobro.Recibo
import caja.comun.CAJA
import caja.comun.LIMA
import caja.comun.LINEA_RECIBO
import caja.comun.RECIBO
import caja.comun.Registros
import org.springframework.stereotype.Service
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

// el original de un recibo: solo lo obtiene el cajero que lo emitió, el mismo día, con su turno abierto. cualquier
// otra copia es un duplicado, que dice que lo es (llega con la consulta de recibos). hoy todo turno del día está
// abierto: el cierre añadirá aquí esa condición. se lee como el usuario que llama: sin READ sobre recibo, 403 de core
@Service
class OriginalDelRecibo(
    private val registros: Registros,
    private val currentUser: CurrentUser,
    private val pdf: ReciboPdf,
    private val reloj: Clock
) {
    suspend fun pdf(numeroImpreso: String): ByteArray {
        val usuario = currentUser.require()
        val numero = numeroImpreso.trim().uppercase(Locale.ROOT)
        val recibo =
            registros.primero(RECIBO, Recibo::class.java, mapOf("numero_impreso" to numero))
                ?: throw NotFoundException("No hay ningún recibo $numero")
        val hoy = LocalDate.now(reloj)
        if (recibo.cajero != usuario.email || recibo.emitidoEn!!.atZone(LIMA).toLocalDate() != hoy) {
            throw ConflictException(
                "El original del recibo $numero solo lo imprime quien lo emitió, el mismo día y con su turno abierto: pida un duplicado"
            )
        }
        val caja = registros.get(CAJA, Caja::class.java, UUID.fromString(recibo.caja))
        val lineas = registros.all(LINEA_RECIBO, LineaRecibo::class.java, filters = mapOf("recibo" to recibo.id!!))
        return pdf.original(recibo, caja, lineas)
    }
}
