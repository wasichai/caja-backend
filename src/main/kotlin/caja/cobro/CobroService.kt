package caja.cobro

import caja.comun.CAJA
import caja.comun.Candados
import caja.comun.Importe
import caja.comun.LIMA
import caja.comun.LINEA_RECIBO
import caja.comun.ORDEN_DE_COBRO
import caja.comun.Observacion
import caja.comun.PAGO_EVENTO
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.TURNO
import caja.comun.Transaccion
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

// el acto de ventanilla (CobrarOrdenes de caja): cobra órdenes y emite su recibo en UNA transacción. el turno, el
// número de la serie, el recibo con sus líneas, las órdenes PAGADA con su recibo y el evento PAGO_REGISTRADO en el
// buzón se confirman juntos o no queda nada: si la fila del buzón está, el recibo está.
//
// wasichai no bloquea filas ni tiene unicidad compuesta: cada decisión se toma bajo un candado consultivo de la
// transacción (Candados), con lo leído DESPUÉS de tomarlo. el orden de los candados es siempre el mismo, para que dos
// cobros no se esperen en cruz: turno:<clave_turno> → turno:<id del turno> → orden:<id> de cada orden, ordenadas por
// id → serie:<serie de la caja>. los unique de clave_turno, numero_impreso, clave_idempotencia y evento_id son la red:
// si uno salta, la transacción entera se revierte y el cobro contesta 409, sin datos a medias y sin reintentar dentro
// (postgres no deja leer nada en una transacción abortada).
//
// todo pasa por RecordService como el usuario que llama: sus permisos son los de core
@Service
class CobroService(
    private val registros: Registros,
    private val candados: Candados,
    private val transaccion: Transaccion,
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val reloj: Clock
) {
    suspend fun cobrar(
        body: NuevoCobro,
        idempotencia: String?
    ): CobroRespuesta {
        val usuario = currentUser.require()
        exigirPermisos(usuario)
        val cajero = cajeroDeLaSesion(body.cajero, usuario.email)
        val hoy = LocalDate.now(reloj)
        val pedido = pedido(body, idempotencia, hoy)
        return try {
            transaccion.en { emitir(pedido, cajero, hoy) }
        } catch (choque: DataIntegrityViolationException) {
            // DuplicateKeyException incluida: la red de un unique saltó. la transacción ya se revirtió entera
            throw ConflictException("El cobro chocó con otro que se confirmó a la vez y no se emitió nada: vuelva a intentarlo")
                .apply { initCause(choque) }
        }
    }

    // dentro de la transacción, en el orden de los candados
    private suspend fun emitir(
        pedido: Pedido,
        cajero: String,
        hoy: LocalDate
    ): CobroRespuesta {
        val caja = cajaQueCobra(pedido.caja)
        val cajaId = caja.id!!

        // 1. el turno: el primer cobro del día lo abre, una vez. bajo su candado se busca y, si no está, se crea
        val claveTurno = "$cajaId|$cajero|$hoy"
        candados.bloquear("turno:$claveTurno")
        val turno =
            registros.primero(TURNO, Turno::class.java, mapOf("clave_turno" to claveTurno))
                ?: registros.create(
                    TURNO,
                    Turno::class.java,
                    mapOf(
                        "caja" to cajaId,
                        "cajero" to cajero,
                        "fecha" to hoy.toString(),
                        "abierto_en" to OffsetDateTime.now(reloj).toString(),
                        "observacion" to pedido.observacion.texto,
                        "clave_turno" to claveTurno
                    )
                )
        val turnoId = turno.id!!

        // 2. el candado del turno: el mismo que toman la anulación y el cierre, para que un cobro no se cuele en un
        // cierre en curso
        candados.bloquear("turno:$turnoId")

        // 3. el reenvío del mismo intento: bajo el candado del turno, dos reenvíos de la misma clave se ordenan
        pedido.clave?.let { clave ->
            registros.primero(RECIBO, Recibo::class.java, mapOf("clave_idempotencia" to clave))?.let { return reenviado(it, cajaId, cajero) }
        }

        // 4. las órdenes, cada una bajo su candado, en orden de id. se leen después de tomarlos: la decisión es sobre
        // lo que hay ahora, nunca sobre una lectura previa
        val porId = pedido.ordenes.map(UUID::toString)
        porId.sorted().forEach { candados.bloquear("orden:$it") }
        val leidas = registros.byIds(ORDEN_DE_COBRO, OrdenDeCobro::class.java, porId)
        val ordenes = porId.map { leidas[it] ?: throw NotFoundException("No hay ninguna orden de cobro $it") }
        val sistema = sistemaUnico(ordenes)
        ordenes.forEach { orden -> motivoNoCobrable(orden, hoy)?.let { throw ConflictException(it) } }

        // 5. el número: el siguiente de la serie, bajo su candado. no deja huecos: si algo falla después, el recibo no
        // se confirma y el número vuelve a estar libre
        val serie = caja.serie!!.trim().uppercase(Locale.ROOT)
        candados.bloquear("serie:$serie")
        val ultimo = registros.primero(RECIBO, Recibo::class.java, mapOf("serie" to serie), sort = "numero", descending = true)
        val numero = (ultimo?.numero ?: 0) + 1

        // 6. el recibo y sus líneas, una por orden. el pagador es el de la primera orden (pagadorDe de caja): es
        // legítimo pagar la deuda de otro, y no se inventa una mezcla
        val primera = ordenes.first()
        val recibo =
            registros.create(
                RECIBO,
                Recibo::class.java,
                mapOf(
                    "serie" to serie,
                    "numero" to numero,
                    "numero_impreso" to numeroImpreso(serie, numero),
                    "caja" to cajaId,
                    "turno" to turnoId,
                    "cajero" to cajero,
                    "pagador_documento" to primera.pagadorDocumento,
                    "pagador_nombre" to primera.pagadorNombre,
                    "pagador_externo_id" to primera.pagadorExternoId,
                    "emitido_en" to OffsetDateTime.now(reloj).toString(),
                    "forma_pago" to pedido.formaPago,
                    "tipo_pago" to NORMAL,
                    "total" to totalDe(ordenes.map { it.importe!! }).toPlainString(),
                    // regla 9: la fecha a la que están los importes es la de pago (CobrarOrdenes.java:180)
                    "actualizado_a" to hoy.toString(),
                    "clave_idempotencia" to pedido.clave,
                    "observacion" to pedido.observacion.texto
                )
            )
        val reciboId = recibo.id!!
        val lineas =
            ordenes.map { orden ->
                registros.create(
                    LINEA_RECIBO,
                    LineaRecibo::class.java,
                    mapOf(
                        "recibo" to reciboId,
                        "orden" to orden.id,
                        "sistema_origen" to orden.sistemaOrigen,
                        "concepto" to orden.concepto,
                        "detalle" to orden.detalle,
                        "referencia_externa" to orden.referenciaExterna,
                        "monto" to orden.importe!!.toPlainString()
                    )
                )
            }

        // 7. las órdenes PAGADA con su recibo (orden_recibo_ck: PAGADA nombra su recibo). replace lee, mezcla y escribe
        // sin control de versión: va bajo el candado de la orden, tomado arriba
        ordenes.forEach { orden ->
            registros.replace(ORDEN_DE_COBRO, OrdenDeCobro::class.java, UUID.fromString(orden.id), mapOf("estado" to PAGADA, "recibo" to reciboId))
        }

        // 8. el evento, en la misma transacción. el pagoId lo genera la caja al cobrar: un reintento de entrega manda el
        // mismo y el origen deduplica
        val pagoId = UUID.randomUUID()
        val evento =
            registros.create(
                PAGO_EVENTO,
                PagoEvento::class.java,
                mapOf(
                    "evento_id" to pagoId.toString(),
                    "tipo" to PAGO_REGISTRADO,
                    "sistema_destino" to sistema,
                    "recibo" to reciboId,
                    "turno" to turnoId,
                    "cuerpo" to cuerpoPagoRegistrado(pagoId, recibo, ordenes),
                    "estado" to EVENTO_PENDIENTE,
                    "intentos" to 0
                )
            )
        return respuesta(recibo, lineas, evento, emitido = true)
    }

    // el recibo de la primera vez, con el MISMO pagoId: uno nuevo dejaría al cliente creyendo que hubo dos pagos. la
    // clave es del cajero que la mandó: la de otro cobro, de otro cajero o de otra caja, es un choque, no un reenvío
    private suspend fun reenviado(
        recibo: Recibo,
        cajaId: String,
        cajero: String
    ): CobroRespuesta {
        if (recibo.cajero != cajero || recibo.caja != cajaId) {
            throw ConflictException("La Idempotency-Key ya nombra el recibo ${recibo.numeroImpreso} de otro cobro: mande una nueva")
        }
        val reciboId = recibo.id!!
        val lineas = registros.all(LINEA_RECIBO, LineaRecibo::class.java, filters = mapOf("recibo" to reciboId))
        val evento =
            registros.primero(PAGO_EVENTO, PagoEvento::class.java, mapOf("recibo" to reciboId, "tipo" to PAGO_REGISTRADO))
                ?: error("El recibo ${recibo.numeroImpreso} no tiene su evento PAGO_REGISTRADO: se emitieron juntos")
        return respuesta(recibo, lineas, evento, emitido = false)
    }

    // la caja por su código: 404 si no existe, 409 si se dio de baja
    private suspend fun cajaQueCobra(codigo: String): Caja {
        val caja =
            registros.primero(CAJA, Caja::class.java, mapOf("codigo" to codigo))
                ?: throw NotFoundException("No hay ninguna caja con el código '$codigo'")
        if (caja.activa != true) throw ConflictException("La caja $codigo está dada de baja y no puede cobrar")
        return caja
    }

    // emitir un recibo y marcar sus órdenes: sin los dos permisos, 403 antes de empezar, diciendo lo que falta
    // (EmisionMasivaService.exigirPermisos de srtm). los demás (turno, línea, evento) los aplica core al escribir
    private suspend fun exigirPermisos(usuario: AuthenticatedUser) {
        val faltan =
            listOfNotNull(
                "creación sobre $RECIBO".takeUnless { puede(usuario, Actions.CREATE, RECIBO) },
                "edición sobre $ORDEN_DE_COBRO".takeUnless { puede(usuario, Actions.UPDATE, ORDEN_DE_COBRO) }
            )
        if (faltan.isNotEmpty()) {
            throw ForbiddenException(
                "Cobrar exige permiso de ${faltan.joinToString(" y de ")}: el cobro emite el recibo y marca las órdenes PAGADA"
            )
        }
    }

    private suspend fun puede(
        usuario: AuthenticatedUser,
        accion: String,
        objeto: String
    ): Boolean =
        try {
            currentUser.requirePermission(usuario, accion, metadata.loadDefinition(usuario.organizationId, objeto).obj.id)
            true
        } catch (_: ForbiddenException) {
            false
        }

    // las reglas sobre la petición, con todos los campos que fallan en un solo 400
    private fun pedido(
        body: NuevoCobro,
        idempotencia: String?,
        hoy: LocalDate
    ): Pedido {
        val errores = mutableListOf<FieldViolation>()

        fun <T> campo(regla: () -> T): T? =
            try {
                regla()
            } catch (e: ValidationException) {
                errores += e.violations
                null
            }

        campo { sinCamposDesconocidos(body.desconocidos) }
        val clave = campo { claveDeIdempotencia(idempotencia) }
        val caja = campo { codigoDeCaja(body.caja) }
        val forma = campo { formaDePago(body.formaPago) }
        campo { fechaDePago(body.fechaDePago, hoy) }
        val ordenes = campo { ordenesMarcadas(body.ordenes) }
        val observacion = campo { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("El cobro no es válido", errores)
        return Pedido(caja!!, forma!!, ordenes!!, observacion!!, clave)
    }

    private fun respuesta(
        recibo: Recibo,
        lineas: List<LineaRecibo>,
        evento: PagoEvento,
        emitido: Boolean
    ): CobroRespuesta {
        val fecha = recibo.actualizadoA!!
        return CobroRespuesta(
            recibo =
                ReciboRespuesta(
                    numeroImpreso = recibo.numeroImpreso!!,
                    serie = recibo.serie!!,
                    numero = recibo.numero!!,
                    cajero = recibo.cajero!!,
                    formaPago = recibo.formaPago!!,
                    tipoPago = recibo.tipoPago!!,
                    emitidoEn =
                        recibo.emitidoEn!!
                            .atZone(LIMA)
                            .toOffsetDateTime()
                            .toString(),
                    total = Importe.de(recibo.total!!, fecha),
                    // por orden: las líneas de un recibo nacen en la misma transacción, con el mismo created_at, y
                    // el reenvío tiene que decir lo mismo que la primera vez
                    lineas =
                        lineas.sortedBy { it.orden }.map {
                            LineaRespuesta(it.orden, it.sistemaOrigen, it.concepto!!, it.detalle, it.referenciaExterna, Importe.de(it.monto!!, fecha))
                        }
                ),
            pagoId = evento.eventoId!!,
            estadoDelPago = if (evento.estado == EVENTO_PENDIENTE) EN_TRANSITO else evento.estado!!,
            emitido = emitido
        )
    }

    private class Pedido(
        val caja: String,
        val formaPago: String,
        val ordenes: List<UUID>,
        val observacion: Observacion,
        val clave: String?
    )
}
