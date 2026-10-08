package caja.cobro

import caja.comun.Candado
import caja.comun.Candados
import caja.comun.ORDEN_DE_COBRO
import caja.comun.Observacion
import caja.comun.PAGO_EVENTO
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.campo
import caja.comun.codigoDeCaja
import caja.comun.sinCamposDesconocidos
import caja.modelo.EVENTO_PENDIENTE
import caja.modelo.LineaRecibo
import caja.modelo.NORMAL
import caja.modelo.OrdenDeCobro
import caja.modelo.PAGADA
import caja.modelo.PAGO_REGISTRADO
import caja.modelo.PagoEvento
import caja.modelo.Recibo
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

// el cobro de órdenes (CobrarOrdenes de caja): cobra órdenes y emite su recibo con el acto de ventanilla (Ventanilla),
// en UNA transacción. a lo común agrega sus candados de orden, las órdenes PAGADA con su recibo y el evento
// PAGO_REGISTRADO en el buzón: si la fila del buzón está, el recibo está. y la vista previa, con las mismas reglas
@Service
class CobroService(
    private val ventanilla: Ventanilla,
    private val registros: Registros,
    private val candados: Candados,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val reloj: Clock
) {
    suspend fun cobrar(
        body: NuevoCobro,
        idempotencia: String?
    ): CobroRespuesta {
        val usuario = currentUser.require()
        // emitir un recibo y marcar sus órdenes. los demás (turno, línea, evento) los aplica core al escribir
        permisos.exigir(
            usuario,
            "Cobrar",
            "el cobro emite el recibo y marca las órdenes PAGADA",
            Actions.CREATE to RECIBO,
            Actions.UPDATE to ORDEN_DE_COBRO
        )
        val cajero = cajeroDeLaSesion(body.cajero, usuario.email)
        val hoy = LocalDate.now(reloj)
        val pedido = pedido(body, idempotencia, cajero, hoy)
        return ventanilla.cobrar(
            pedido.apertura,
            preparar = { ordenes(pedido.ordenes, hoy) },
            despues = ::pagar,
            reenviado = ::reenviado
        )
    }

    // lo que costaría cobrar esas órdenes hoy, sin candados ni escritura: las mismas reglas que el cobro
    // (impedimentosDelCobro, lineaDeOrden, totalDe), y lo que lo impediría va en motivos. la regla de la de tasas: una
    // orden que no se puede cobrar (pagada, anulada, todavía no exigible o con el importe roto) no entra en las líneas
    // ni en el total
    suspend fun vistaPrevia(body: VistaPreviaDeOrdenes): VistaPrevia {
        val usuario = currentUser.require()
        permisos.exigir(usuario, "La vista previa", "lee las órdenes que se cobrarían", Actions.READ to ORDEN_DE_COBRO)
        val hoy = LocalDate.now(reloj)
        val errores = mutableListOf<FieldViolation>()
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "una vista previa") }
        val ids = campo(errores) { ordenesMarcadas(body.ordenes) }
        campo(errores) { fechaDePago(body.fechaDePago, hoy) }
        if (errores.isNotEmpty()) throw ValidationException("La vista previa no es válida", errores)

        val porId = ids!!.map(UUID::toString)
        val leidas = registros.byIds(ORDEN_DE_COBRO, OrdenDeCobro::class.java, porId)
        val cobrables = porId.mapNotNull(leidas::get).filter { motivoNoCobrable(it, hoy) == null }
        return vistaPrevia(cobrables.map(::lineaDeOrden), hoy, impedimentosDelCobro(porId, leidas, hoy))
    }

    // dentro de la transacción, entre el candado del turno y el de la serie: las órdenes, cada una bajo su candado, en
    // orden de id. se leen después de tomarlos: la decisión es sobre lo que hay ahora, nunca sobre una lectura previa
    private suspend fun ordenes(
        ordenes: List<UUID>,
        hoy: LocalDate
    ): Ventanilla.Contenido<List<OrdenDeCobro>> {
        val porId = ordenes.map(UUID::toString)
        porId.sorted().forEach { candados.bloquear(Candado.ORDEN, it) }
        val leidas = registros.byIds(ORDEN_DE_COBRO, OrdenDeCobro::class.java, porId)
        impedimentosDelCobro(porId, leidas, hoy).firstOrNull()?.let { throw it }
        val cobradas = porId.map(leidas::getValue)
        // el pagador es el de la primera orden (pagadorDe de caja): es legítimo pagar la deuda de otro, y no se inventa
        // una mezcla
        val primera = cobradas.first()
        return Ventanilla.Contenido(
            Pagador(primera.pagadorDocumento, primera.pagadorNombre, primera.pagadorExternoId),
            cobradas.map(::lineaDeOrden),
            cobradas
        )
    }

    // las órdenes PAGADA con su recibo y el evento, en la misma transacción que el recibo
    private suspend fun pagar(
        emitido: Ventanilla.Emitido,
        ordenes: List<OrdenDeCobro>
    ): CobroRespuesta {
        val reciboId = emitido.recibo.id!!
        // orden_recibo_ck: PAGADA nombra su recibo. replace lee, mezcla y escribe sin control de versión: va bajo el
        // candado de la orden, tomado al prepararlas
        ordenes.forEach { orden ->
            registros.replace(ORDEN_DE_COBRO, OrdenDeCobro::class.java, UUID.fromString(orden.id), mapOf("estado" to PAGADA, "recibo" to reciboId))
        }

        // el pagoId lo genera la caja al cobrar: un reintento de entrega manda el mismo y el origen deduplica
        val pagoId = UUID.randomUUID()
        val evento =
            registros.create(
                PAGO_EVENTO,
                PagoEvento::class.java,
                mapOf(
                    "evento_id" to pagoId.toString(),
                    "tipo" to PAGO_REGISTRADO,
                    "sistema_destino" to sistemaUnico(ordenes),
                    "recibo" to reciboId,
                    "turno" to emitido.turno,
                    "cuerpo" to cuerpoPagoRegistrado(pagoId, emitido.recibo, ordenes),
                    "estado" to EVENTO_PENDIENTE,
                    "intentos" to 0
                )
            )
        return respuestaDelCobro(emitido.recibo, emitido.lineas, evento, emitido = true)
    }

    // el recibo de la primera vez, con el MISMO pagoId: uno nuevo dejaría al cliente creyendo que hubo dos pagos
    private suspend fun reenviado(
        recibo: Recibo,
        lineas: List<LineaRecibo>
    ): CobroRespuesta {
        val evento =
            registros.primero(PAGO_EVENTO, PagoEvento::class.java, mapOf("recibo" to recibo.id!!, "tipo" to PAGO_REGISTRADO))
                ?: error("El recibo ${recibo.numeroImpreso} no tiene su evento PAGO_REGISTRADO: se emitieron juntos")
        return respuestaDelCobro(recibo, lineas, evento, emitido = false)
    }

    // las reglas sobre la petición, con todos los campos que fallan en un solo 400
    private fun pedido(
        body: NuevoCobro,
        idempotencia: String?,
        cajero: String,
        hoy: LocalDate
    ): Pedido {
        val errores = mutableListOf<FieldViolation>()
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "un cobro") }
        val clave = campo(errores) { claveDeIdempotencia(idempotencia) }
        val caja = campo(errores) { codigoDeCaja(body.caja) }
        val forma = campo(errores) { formaDePago(body.formaPago) }
        campo(errores) { fechaDePago(body.fechaDePago, hoy) }
        val ordenes = campo(errores) { ordenesMarcadas(body.ordenes) }
        val observacion = campo(errores) { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("El cobro no es válido", errores)
        return Pedido(Ventanilla.Apertura(caja!!, cajero, hoy, forma!!, observacion!!, clave, NORMAL), ordenes!!)
    }

    private class Pedido(
        val apertura: Ventanilla.Apertura,
        val ordenes: List<UUID>
    )
}
