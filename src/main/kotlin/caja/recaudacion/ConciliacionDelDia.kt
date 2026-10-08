package caja.recaudacion

import caja.buzon.ClienteDelSistemaDeOrigen
import caja.comun.Importe
import caja.comun.PAGO_EVENTO
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.TURNO
import caja.comun.Transaccion
import caja.modelo.PagoEvento
import caja.modelo.Turno
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.LocalDate

// la conciliación del día (ConciliacionDelDia y ConciliacionController de caja, ADR-0026 §3). no es un proceso
// silencioso: es una operación de negocio con su pantalla, y si no cuadra, el día no cierra.
//
// dos mitades, leídas aparte y por eso en dos tiempos:
// 1. LO QUE LA CAJA SABE SOLA, en una foto de la base (Transaccion.lectura): los pago_evento de los TURNOS de ese día
//    (no de los instantes), con el total de su recibo, contados por sistema de destino;
// 2. LO QUE DICE EL ORIGEN, FUERA DE TODA TRANSACCIÓN: GET {url}/pagos/conciliacion?fecha= a cada sistema, con el
//    destino de caja.buzon.destinos.<sistema> (su url, su token y su timeout). si no contesta, o no hay a dónde
//    preguntar, la línea lo dice y no trae ceros
@Service
class ConciliacionDelDia(
    private val registros: Registros,
    private val transaccion: Transaccion,
    private val cliente: ClienteDelSistemaDeOrigen,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val reloj: Clock
) {
    suspend fun de(fecha: String?): ConciliacionRespuesta {
        val usuario = currentUser.require()
        permisos.exigir(
            usuario,
            "Ver la conciliación del día",
            "cuenta los pagos del buzón y suma sus recibos",
            Actions.READ to PAGO_EVENTO,
            Actions.READ to RECIBO
        )
        val dia = fechaDeLaConciliacion(fecha)
        val hoy = LocalDate.now(reloj)
        val recuentos =
            transaccion.lectura {
                val turnos = registros.all(TURNO, Turno::class.java, filters = mapOf("fecha" to dia.toString()))
                val eventos = registros.byRelation(PAGO_EVENTO, PagoEvento::class.java, "turno", turnos.map { it.id!! })
                val recibos = registros.byIds(RECIBO, ReciboLeido::class.java, eventos.map { it.recibo!! })
                recuentosDe(eventos.map { EventoDelDia(it.sistemaDestino!!, it.tipo!!, it.estado!!, recibos.getValue(it.recibo!!).total!!) })
            }
        // la fecha con la que se pregunta es EXACTAMENTE la que se contó: preguntar por otra es como se producen las
        // conciliaciones que cuadran comparando dos días distintos
        val lineas = recuentos.map { lineaDe(it, dia, cliente.leer(it.sistema, "/pagos/conciliacion?fecha=$dia")) }
        return ConciliacionRespuesta(
            fecha = dia.toString(),
            aLaFecha = hoy.toString(),
            cuadra = cuadraElDia(lineas),
            lineas = lineas.map { respuestaDe(it, dia) }
        )
    }

    // las cifras de la línea van a la fecha conciliada: son las de los recibos de ese día y lo que el origen aplicó ese día
    private fun respuestaDe(
        linea: LineaDeConciliacion,
        dia: LocalDate
    ): LineaDeConciliacionRespuesta {
        val recuento = linea.recuento
        val aplicado = linea.aplicado
        return LineaDeConciliacionRespuesta(
            sistemaDestino = recuento.sistema,
            registrados = recuento.registrados,
            anulados = recuento.anulados,
            enTransito = recuento.enTransito,
            muertos = recuento.muertos,
            explicados = recuento.explicados,
            cobrado = Importe.de(recuento.cobrado, dia),
            anulado = Importe.de(recuento.anulado, dia),
            neto = Importe.de(recuento.neto, dia),
            recibidos = aplicado?.recibidos,
            aplicados = aplicado?.aplicados,
            rechazados = aplicado?.rechazados,
            importeAplicado = aplicado?.let { Importe.de(it.importeAplicado, dia) },
            diferencia = linea.diferencia?.let { Importe.de(it, dia) },
            porQueNoSeSabe = linea.porQueNoSeSabe,
            cuadra = linea.cuadra()
        )
    }
}
