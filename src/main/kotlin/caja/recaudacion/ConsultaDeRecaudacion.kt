package caja.recaudacion

import caja.comun.ANULACION_RECIBO
import caja.comun.AREA
import caja.comun.CAJA
import caja.comun.Importe
import caja.comun.LINEA_RECIBO
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.TASA
import caja.comun.TURNO
import caja.comun.Transaccion
import caja.modelo.AnulacionRecibo
import caja.modelo.Area
import caja.modelo.Caja
import caja.modelo.Tasa
import caja.modelo.Turno
import caja.modelo.filtroDelTurno
import caja.turno.ArqueoDelTurno
import caja.turno.ArqueoRespuesta
import caja.turno.EstadoDelTurno
import caja.turno.LibroDelTurno
import caja.turno.ReciboRotoRespuesta
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.SqlIdentifier
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

// el avance de recaudación y la recaudación por área y partida (ConsultaDeRecaudacion, RecaudacionController y
// RecaudacionRepositoryJdbc de caja, #36, RF-088, RF-089). solo lee, como el usuario que llama (los permisos son los de
// core), y las cifras salen de los recibos y sus líneas congelados, no de un libro.
//
// EL RANGO ES EL DEL TURNO, NO EL DEL RELOJ: la recaudación de un día son los recibos de los TURNOS de ese día. turno.fecha
// es un día de Lima; recibo.emitido_en es un instante, y con él la frontera de la medianoche dependería de la zona de
// quien consulta: un cobro de las nueve de la noche saldría del día en que se cobró. y el arqueo del turno usa la fecha
// del turno: si el reporte usara otra cosa, la suma de los arqueos de un mes podría no ser la recaudación del mes.
//
// UN RECIBO ROTO NO TUMBA EL REPORTE. un recibo con cifras imposibles (defectoDeRecaudacion: un total o una anulación
// en negativo, una anulación mayor que el total, una línea de su cobro negativa) no lo escribe caja: llega por la base,
// o es de antes de GuardiaDeEscrituras, que cierra la API genérica (caja-backend#20). queda fuera de las cifras,
// entero (el avance y la recaudación por área cuentan los mismos recibos), y se nombra en recibos_con_datos_rotos. sin
// esto, un solo recibo así daría un 500 en todo rango que incluyera su día.
//
// SIN CANDADOS, y ese es el punto: el avance se mira MIENTRAS el cajero cobra, y una lectura que tomara el candado del
// turno pondría la cola de la ventanilla a esperar por un informe. cada consulta lee en UNA foto de la base
// (Transaccion.lectura, REPEATABLE READ): los turnos, sus recibos, sus anulaciones y sus líneas cuadran entre sí aunque
// un cobro se confirme entre una lectura y otra
@Service
class ConsultaDeRecaudacion(
    private val registros: Registros,
    private val libro: LibroDelTurno,
    private val transaccion: Transaccion,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val reloj: Clock
) {
    // lo recaudado en el rango, por origen. con caja y cajero, además el arqueo en vivo de su turno de hoy (404 si no lo
    // abrió: un arqueo en ceros haría pensar que abrió y no cobró). caja y cajero, cada uno, también filtran el avance
    suspend fun avance(
        desde: String?,
        hasta: String?,
        origen: String?,
        caja: String?,
        cajero: String?
    ): AvanceRespuesta {
        val usuario = currentUser.require()
        permisos.exigir(
            usuario,
            "Ver el avance de recaudación",
            "suma los recibos y sus líneas",
            Actions.READ to RECIBO,
            Actions.READ to LINEA_RECIBO
        )
        val hoy = LocalDate.now(reloj)
        val rango = rangoPedido(desde, hasta, hoy)
        val laCaja = caja?.trim()?.ifEmpty { null }
        val elCajero = cajero?.trim()?.ifEmpty { null }
        return transaccion.lectura {
            // una caja o un cajero que no existen son un 400 en su campo, no un avance en cero: una errata se leería como
            // «no cobró nada». un cajero existe para la caja si abrió algún turno, cualquier día
            val errores = mutableListOf<FieldViolation>()
            val cajaLeida = laCaja?.let { registros.primero(CAJA, Caja::class.java, mapOf("codigo" to it)) }
            if (laCaja != null && cajaLeida == null) {
                errores += FieldViolation("caja", "no hay ninguna caja con el código '$laCaja'")
            }
            if (elCajero != null && registros.count(TURNO, mapOf("cajero" to elCajero)) == 0L) {
                errores += FieldViolation("cajero", "'$elCajero' no abrió ningún turno, ningún día: no hay nada suyo que sumar")
            }
            if (errores.isNotEmpty()) throw ValidationException("El avance no es válido", errores)
            val turno = if (cajaLeida != null && elCajero != null) turnoDeHoy(laCaja, cajaLeida, elCajero, hoy) else null
            val juzgados =
                juzgados(registros.byRelation(RECIBO, ReciboLeido::class.java, "turno", turnosDelRango(rango, cajaLeida?.id, elCajero).map { it.id!! }))
            val recibos =
                juzgados.filter { it.defecto == null }.map {
                    ReciboRecaudado(origenDelRecibo(it.recibo.tipoPago!!, it.lineas.map { l -> l.sistemaOrigen }), it.recibo.total!!, it.anulado)
                }
            val avance = Avance.de(recibos.filter { esDelOrigen(it.origen, origen) })
            AvanceRespuesta(
                desde = rango.desde.toString(),
                hasta = rango.hasta.toString(),
                aLaFecha = hoy.toString(),
                filas =
                    avance.filas.map {
                        FilaDeOrigenRespuesta(it.origen, Importe.de(it.cobrado, hoy), Importe.de(it.anulado, hoy), Importe.de(it.neto, hoy))
                    },
                cobrado = Importe.de(avance.cobrado, hoy),
                anulado = Importe.de(avance.anulado, hoy),
                neto = Importe.de(avance.neto, hoy),
                turno = turno,
                recibosConDatosRotos = rotos(juzgados)
            )
        }
    }

    // lo recaudado en el rango por área, partida y concepto. area: el código o la etiqueta «COD — nombre». solo las
    // líneas de tasas tienen área y partida: con un área pedida, lo de las órdenes queda fuera porque no consta en ninguna
    suspend fun porArea(
        area: String?,
        desde: String?,
        hasta: String?
    ): PorAreaRespuesta {
        val usuario = currentUser.require()
        permisos.exigir(
            usuario,
            "Ver la recaudación por área",
            "suma las líneas de los recibos con el área y la partida de su tasa",
            Actions.READ to RECIBO,
            Actions.READ to LINEA_RECIBO,
            Actions.READ to AREA,
            Actions.READ to TASA
        )
        val hoy = LocalDate.now(reloj)
        val rango = rangoPedido(desde, hasta, hoy)
        val codigo = codigoDeArea(area)
        return transaccion.lectura {
            val juzgados = juzgados(registros.byRelation(RECIBO, ReciboLeido::class.java, "turno", turnosDelRango(rango, null, null).map { it.id!! }))
            val lineas = juzgados.filter { it.defecto == null }.flatMap { j -> j.lineas.map { it to j.tieneActa } }
            val tasas = registros.byIds(TASA, Tasa::class.java, lineas.mapNotNull { it.first.tasa })
            val areas = registros.byIds(AREA, Area::class.java, tasas.values.mapNotNull { it.area })
            val recaudadas =
                lineas
                    .map { (linea, anulada) ->
                        val tasa = linea.tasa?.let { tasas[it] }
                        val suArea = tasa?.area?.let { areas[it] }
                        LineaRecaudada(
                            area = suArea?.codigo,
                            areaNombre = suArea?.nombre,
                            partida = tasa?.partidaPresupuestal,
                            concepto = tasa?.codigo ?: linea.sistemaOrigen,
                            monto = linea.monto!!,
                            anulada = anulada
                        )
                    }.filter { codigo == null || it.area == codigo }
            val distribucion = Distribucion.de(recaudadas)
            PorAreaRespuesta(
                desde = rango.desde.toString(),
                hasta = rango.hasta.toString(),
                aLaFecha = hoy.toString(),
                filas =
                    distribucion.filas.map {
                        FilaDePartidaRespuesta(
                            it.area,
                            it.areaNombre,
                            it.partida,
                            it.concepto,
                            Importe.de(it.cobrado, hoy),
                            Importe.de(it.anulado, hoy),
                            Importe.de(it.neto, hoy)
                        )
                    },
                neto = Importe.de(distribucion.neto, hoy),
                netoSinPartida = Importe.de(distribucion.netoSinPartida, hoy),
                recibosConDatosRotos = rotos(juzgados)
            )
        }
    }

    // un recibo del rango con las líneas de su cobro (las de su sello), lo que devolvió su anulación (el importe del
    // acta, el mismo que resta el arqueo del turno; cero sin acta) y por qué no se puede contar, o null
    private class Juzgado(
        val recibo: ReciboLeido,
        val lineas: List<LineaLeida>,
        val tieneActa: Boolean,
        val anulado: BigDecimal,
        val defecto: String?
    )

    // cada recibo con su anulación y las líneas de su cobro, juzgado por defectoDeRecaudacion
    private suspend fun juzgados(recibos: List<ReciboLeido>): List<Juzgado> {
        val ids = recibos.map { it.id!! }
        val actas =
            registros
                .byRelation(ANULACION_RECIBO, AnulacionRecibo::class.java, "recibo", ids)
                .associate { it.recibo!! to it.importe!! }
        val lineas = registros.byRelation(LINEA_RECIBO, LineaLeida::class.java, "recibo", ids).groupBy { it.recibo!! }
        return recibos.map { recibo ->
            val suyas = delCobro(recibo.createdAt, lineas[recibo.id].orEmpty()) { it.createdAt }
            val anulado = actas[recibo.id] ?: BigDecimal.ZERO
            Juzgado(recibo, suyas, recibo.id in actas, anulado, defectoDeRecaudacion(recibo.total!!, anulado, suyas.map { it.monto }))
        }
    }

    // los que no se pudieron contar, con su porqué, por número
    private fun rotos(juzgados: List<Juzgado>): List<ReciboRotoRespuesta> =
        juzgados
            .mapNotNull { j -> j.defecto?.let { ReciboRotoRespuesta(j.recibo.numeroImpreso ?: j.recibo.id!!, it) } }
            .sortedBy { it.numeroImpreso }

    // los turnos cuya FECHA cae en el rango, los dos días incluidos
    private suspend fun turnosDelRango(
        rango: Rango,
        cajaId: String?,
        cajero: String?
    ): List<Turno> {
        val enElRango =
            RecordCriterion { turno, bind ->
                val fecha = SqlIdentifier.quote(turno.fields.first { it.name == "fecha" }.columnName)
                "$fecha BETWEEN ${bind(rango.desde)} AND ${bind(rango.hasta)}"
            }
        val filtros =
            buildMap {
                cajaId?.let { put("caja", it) }
                cajero?.let { put("cajero", it) }
            }
        return registros.all(TURNO, Turno::class.java, filters = filtros, criteria = listOf(enElRango))
    }

    // el turno de hoy de ese cajero en esa caja, con su arqueo en vivo: el mismo de GET /turnos/{id}/arqueo, sin
    // declarado, reusando ArqueoDelTurno. sin ese turno, 404
    private suspend fun turnoDeHoy(
        codigo: String,
        caja: Caja,
        cajero: String,
        hoy: LocalDate
    ): TurnoDelAvance {
        val turno =
            registros.primero(TURNO, Turno::class.java, filtroDelTurno(caja.id!!, cajero, hoy))
                ?: throw NotFoundException(
                    "El cajero '$cajero' no abrió turno en la caja '$codigo' el $hoy: no hay nada que arquear, y un arqueo en ceros " +
                        "haría pensar que abrió y no cobró"
                )
        val turnoId = turno.id!!
        val recibos = libro.recibos(turnoId)
        return TurnoDelAvance(
            turnoId = turnoId,
            caja = caja.codigo!!,
            cajero = turno.cajero!!,
            fecha = turno.fecha.toString(),
            estadoDelTurno = EstadoDelTurno.de(libro.historia(turnoId)),
            arqueo = ArqueoRespuesta.enVivo(ArqueoDelTurno.de(recibos.contables, emptyMap(), hoy)),
            recibosConDatosRotos = ReciboRotoRespuesta.de(recibos.rotos)
        )
    }
}
