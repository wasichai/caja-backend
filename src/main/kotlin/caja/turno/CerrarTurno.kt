package caja.turno

import caja.comun.CAJA
import caja.comun.CIERRE_TURNO
import caja.comun.CIERRE_TURNO_LINEA
import caja.comun.Candado
import caja.comun.Candados
import caja.comun.Importe
import caja.comun.Observacion
import caja.comun.Permisos
import caja.comun.REVERSION_CIERRE
import caja.comun.Registros
import caja.comun.TURNO
import caja.comun.Transaccion
import caja.comun.campo
import caja.comun.codigoDeCaja
import caja.comun.enLima
import caja.comun.sinCamposDesconocidos
import caja.modelo.Caja
import caja.modelo.Turno
import caja.modelo.filtroDelTurno
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

// cierra el turno de un cajero con su arqueo, y reversa un cierre (CerrarTurno de caja, #36, RF-087).
//
// UN CIERRE NO SE MODIFICA NI SE BORRA: SE REVERSA CON OTRO (regla 4). cerrar agrega un cierre_turno con sus totales
// congelados y sus líneas; reversar agrega una reversion_cierre que lo deja sin efecto y reabre el turno. el acta
// anterior no se toca. aquí solo hay create (InmutabilidadDelReciboTest lo vigila, y roles.json no da UPDATE ni DELETE).
//
// NADA SE CUELA EN UN CIERRE EN CURSO, Y DOS CIERRES NO SE PISAN. el cierre toma el candado del turno (Candado.TURNO, el
// mismo que toman el cobro de órdenes, el de tasas y la anulación) y relee todo después de tomarlo: mientras lo tenga,
// nada de ese turno entra ni sale, y el arqueo que congela no se queda corto entre leerlo y escribirlo; el cobro o la
// anulación que esperaban encuentran el turno cerrado (409). es el ÚNICO candado que toma, así que no se cruza con el
// orden turno-clave → turno → órdenes → serie. la secuencia es común al cierre y a la reversión y la serializa ese
// candado; las uniqueConstraints de (turno, secuencia), de (cierre_turno, forma_pago) y de cierre_revertido son la red: si uno salta
// (DuplicateKeyException), todo se revierte y se contesta 409, sin releer dentro.
//
// EL DESCUADRE SE GUARDA, NO SE RECHAZA. que lo declarado no coincida con el neto no impide cerrar: es justo lo que hay
// que dejar escrito. lo que impide cerrar es un pago que su sistema de origen no conoce (PENDIENTE o MUERTO).
//
// UN RECIBO ROTO NO BLOQUEA EL CIERRE. un recibo con cifras imposibles (un total negativo, una anulación mayor que el
// total) no lo escribe caja: llega por la base, o es de antes de GuardiaDeEscrituras, que cierra la API genérica
// (caja-backend#20). si bloqueara, ese recibo dejaría su turno sin cerrar hasta que alguien lo arreglara en la base.
// queda FUERA del acta (sus cifras y recibos_emitidos son las de los recibos contables),
// se nombra en la respuesta y en una línea ERROR, y el arqueo del turno lo sigue nombrando después de cerrar
@Service
class CerrarTurno(
    private val registros: Registros,
    private val libro: LibroDelTurno,
    private val candados: Candados,
    private val transaccion: Transaccion,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val reloj: Clock
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun cerrar(body: PeticionDeCierre): CierreRespuesta {
        val usuario = currentUser.require()
        // cerrar es el privilegio REGISTRO de cierre_caja: crear el acta y sus líneas
        permisos.exigir(
            usuario,
            "Cerrar un turno",
            "el cierre congela el arqueo del turno en un acta",
            Actions.CREATE to CIERRE_TURNO,
            Actions.CREATE to CIERRE_TURNO_LINEA
        )
        val cajero = cajeroDelTurno(body.cajero, usuario.email)
        val pedido = pedidoDeCierre(body, cajero)
        return try {
            transaccion.en { cerrarEnLaTransaccion(pedido, usuario) }
        } catch (choque: DuplicateKeyException) {
            throw ConflictException(
                "Otro cierre del mismo turno se confirmó a la vez y este no se escribió: vuelva a mirar el turno"
            ).apply { initCause(choque) }
        }
    }

    suspend fun reversar(body: PeticionDeReversion): ReversionRespuesta {
        val usuario = currentUser.require()
        // reversar es el privilegio ELIMINACION de cierre_caja: reabre una caja cuyo arqueo ya estaba firmado
        permisos.exigir(usuario, "Reversar un cierre", "la reversión reabre un turno cuyo arqueo ya estaba firmado", Actions.CREATE to REVERSION_CIERRE)
        val cajero = cajeroDelTurno(body.cajero, usuario.email)
        val pedido = pedidoDeReversion(body, cajero)
        return try {
            transaccion.en { reversarEnLaTransaccion(pedido, usuario) }
        } catch (choque: DuplicateKeyException) {
            throw ConflictException(
                "Otra reversión del mismo turno se confirmó a la vez y esta no se escribió: vuelva a mirar el turno"
            ).apply { initCause(choque) }
        }
    }

    private suspend fun cerrarEnLaTransaccion(
        pedido: PedidoDeCierre,
        usuario: AuthenticatedUser
    ): CierreRespuesta {
        // 1 y 2. el turno de la caja, del cajero de la sesión y de esa fecha, y su candado. todo lo que sigue se lee
        // después de tomarlo
        val (caja, turno) = turnoBloqueado(pedido.caja, pedido.cajero, pedido.fecha)
        val turnoId = turno.id!!
        val historia = libro.historia(turnoId)

        // 3. cerrar dos veces dejaría dos arqueos vigentes sobre el mismo dinero
        if (EstadoDelTurno.de(historia) == EstadoDelTurno.CERRADO) {
            throw ConflictException(
                "El turno de ${turno.cajero} en la caja ${caja.codigo} del ${turno.fecha} ya está cerrado. Un cierre no se modifica: si " +
                    "hay que rehacerlo, se reversa el que hay (eso reabre el turno) y se cierra otra vez"
            )
        }

        // 4. un pago que su sistema de origen no conoce impide cerrar, y se dice cuál
        libro.pagosSinEntregar(turnoId).takeIf { it.isNotEmpty() }?.let { throw HayPagosSinEntregar(it) }

        // 5. el arqueo con lo declarado, a la fecha del turno, y su cuadre: las dos mitades suman el neto. no puede
        // fallar sin un defecto (las dos salen de los mismos recibos); se comprueba porque el precio es cero
        val recibos = libro.recibos(turnoId)
        val arqueo = ArqueoDelTurno.de(recibos.contables, pedido.declarado, pedido.fecha)
        val cuadre = Cuadre.de(recibos.contables)
        check(cuadre.sumaElNetoDe(arqueo)) {
            "El arqueo del turno $turnoId dice ${arqueo.neto.toPlainString()} y las dos mitades del cuadre suman " +
                "${cuadre.total.toPlainString()}: el reparto entre con evento y sin evento deja algún recibo fuera"
        }

        // 6. el acta y una línea por forma de pago, con la secuencia siguiente
        val secuencia = siguienteSecuencia(historia)
        val cierre =
            registros.create(
                CIERRE_TURNO,
                CierreTurno::class.java,
                mapOf(
                    "turno" to turnoId,
                    "secuencia" to secuencia,
                    "fecha" to pedido.fecha.toString(),
                    "registrado_en" to ahora().toString(),
                    "total_cobrado" to arqueo.totalCobrado.toPlainString(),
                    "total_anulado" to arqueo.totalAnulado.toPlainString(),
                    "neto" to arqueo.neto.toPlainString(),
                    "total_declarado" to arqueo.totalDeclarado.toPlainString(),
                    "diferencia" to arqueo.diferencia.toPlainString(),
                    "recibos_emitidos" to arqueo.recibosEmitidos,
                    "recibos_anulados" to arqueo.recibosAnulados,
                    "cobrado_con_evento" to cuadre.conEvento.toPlainString(),
                    "cobrado_sin_evento" to cuadre.sinEvento.toPlainString(),
                    "usuario" to usuario.email,
                    "observacion" to pedido.observacion.texto
                ),
                pedido.observacion.texto
            )
        val cierreId = cierre.id!!
        arqueo.lineas.forEach { linea ->
            registros.create(
                CIERRE_TURNO_LINEA,
                CierreTurnoLinea::class.java,
                mapOf(
                    "cierre_turno" to cierreId,
                    "forma_pago" to linea.formaPago,
                    "cobrado" to linea.cobrado.toPlainString(),
                    "anulado" to linea.anulado.toPlainString(),
                    "neto" to linea.neto.toPlainString(),
                    "declarado" to linea.declarado.toPlainString()
                ),
                pedido.observacion.texto
            )
        }

        // el acta no cuenta los recibos rotos: se dice, aquí y en la respuesta. si la transacción se revierte, la línea
        // sobra, pero nunca falta
        if (recibos.rotos.isNotEmpty()) {
            log.error(
                "CIERRE CON RECIBOS FUERA DEL ARQUEO: el cierre {} del turno {} no cuenta {} recibo(s) con datos rotos, escritos por fuera " +
                    "de caja: {}. Revíselos",
                cierreId,
                turnoId,
                recibos.rotos.size,
                recibos.rotos.joinToString("; ") { "${it.numero}: ${it.motivo}" }.replace(Regex("\\s+"), " ")
            )
        }

        // 7. el acta, con el turno CERRADO
        return CierreRespuesta(
            cierreId = cierreId,
            turnoId = turnoId,
            caja = caja.codigo!!,
            cajero = turno.cajero!!,
            fecha = pedido.fecha.toString(),
            secuencia = secuencia,
            registradoEn = enLima(cierre.registradoEn!!),
            usuario = cierre.usuario!!,
            observacion = cierre.observacion!!,
            estadoDelTurno = EstadoDelTurno.CERRADO,
            arqueo = ArqueoRespuesta.declarado(arqueo),
            cobradoConEvento = Importe.de(cuadre.conEvento, pedido.fecha),
            cobradoSinEvento = Importe.de(cuadre.sinEvento, pedido.fecha),
            recibosConDatosRotos = ReciboRotoRespuesta.de(recibos.rotos)
        )
    }

    private suspend fun reversarEnLaTransaccion(
        pedido: PedidoDeReversion,
        usuario: AuthenticatedUser
    ): ReversionRespuesta {
        val (caja, turno) = turnoBloqueado(pedido.caja, pedido.cajero, pedido.fecha)
        val turnoId = turno.id!!
        val historia = libro.historia(turnoId)
        // el cierre que se reversa no se toca: se agrega la fila que lo deja sin efecto, y una sola (cierre_revertido)
        val vigente =
            cierreVigente(historia)
                ?: throw ConflictException(
                    "Nada que reversar: el turno de ${turno.cajero} en la caja ${caja.codigo} del ${turno.fecha} no está cerrado, no hay " +
                        "ningún arqueo que dejar sin efecto"
                )
        val secuencia = siguienteSecuencia(historia)
        val reversion =
            registros.create(
                REVERSION_CIERRE,
                ReversionCierre::class.java,
                mapOf(
                    "turno" to turnoId,
                    "cierre_revertido" to vigente.id,
                    "secuencia" to secuencia,
                    "motivo" to pedido.motivo,
                    "fecha" to pedido.fecha.toString(),
                    "registrado_en" to ahora().toString(),
                    "usuario" to usuario.email,
                    "observacion" to pedido.observacion.texto
                ),
                pedido.observacion.texto
            )
        return ReversionRespuesta(
            reversionId = reversion.id!!,
            turnoId = turnoId,
            caja = caja.codigo!!,
            cajero = turno.cajero!!,
            fecha = pedido.fecha.toString(),
            secuencia = secuencia,
            cierreRevertido = vigente.id,
            motivo = reversion.motivo!!,
            registradoEn = enLima(reversion.registradoEn!!),
            usuario = reversion.usuario!!,
            observacion = reversion.observacion!!,
            estadoDelTurno = EstadoDelTurno.ABIERTO
        )
    }

    // la caja por su código y el turno de ese cajero en ella ese día (404 si no hay), con el candado del turno tomado.
    // el turno se lee antes del candado porque no cambia nunca (nace bajo TURNO_CLAVE y no se edita); lo que cambia (su
    // historia, sus recibos, sus pagos) se lee después. una caja de baja también cierra: su turno existe
    private suspend fun turnoBloqueado(
        codigo: String,
        cajero: String,
        fecha: LocalDate
    ): Pair<Caja, Turno> {
        val caja =
            registros.primero(CAJA, Caja::class.java, mapOf("codigo" to codigo))
                ?: throw NotFoundException("No hay ninguna caja con el código '$codigo'")
        val turno =
            registros.primero(TURNO, Turno::class.java, filtroDelTurno(caja.id!!, cajero, fecha))
                ?: throw NotFoundException(
                    "El cajero $cajero no abrió turno en la caja $codigo el $fecha: no hay nada que arquear. El turno lo abre el primer cobro del día"
                )
        candados.bloquear(Candado.TURNO, turno.id!!)
        return caja to turno
    }

    private fun ahora(): OffsetDateTime = OffsetDateTime.now(reloj)

    // las reglas sobre la petición, con todos los campos que fallan en un solo 400
    private fun pedidoDeCierre(
        body: PeticionDeCierre,
        cajero: String
    ): PedidoDeCierre {
        val errores = mutableListOf<FieldViolation>()
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "un cierre") }
        val caja = campo(errores) { codigoDeCaja(body.caja) }
        val fecha = campo(errores) { fechaDelTurno(body.fecha, LocalDate.now(reloj)) }
        val declarado = campo(errores) { declaradoPedido(body.declarado) }
        val observacion = campo(errores) { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("El cierre no es válido", errores)
        return PedidoDeCierre(caja!!, cajero, fecha!!, declarado!!, observacion!!)
    }

    private fun pedidoDeReversion(
        body: PeticionDeReversion,
        cajero: String
    ): PedidoDeReversion {
        val errores = mutableListOf<FieldViolation>()
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "una reversión") }
        val caja = campo(errores) { codigoDeCaja(body.caja) }
        val fecha = campo(errores) { fechaDelTurno(body.fecha, LocalDate.now(reloj)) }
        val motivo = campo(errores) { motivoDeReversion(body.motivo) }
        val observacion = campo(errores) { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("La reversión no es válida", errores)
        return PedidoDeReversion(caja!!, cajero, fecha!!, motivo!!, observacion!!)
    }

    private class PedidoDeCierre(
        val caja: String,
        val cajero: String,
        val fecha: LocalDate,
        val declarado: Map<String, BigDecimal>,
        val observacion: Observacion
    )

    private class PedidoDeReversion(
        val caja: String,
        val cajero: String,
        val fecha: LocalDate,
        val motivo: String,
        val observacion: Observacion
    )
}
