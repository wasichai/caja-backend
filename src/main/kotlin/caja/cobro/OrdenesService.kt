package caja.cobro

import caja.comun.Candado
import caja.comun.Candados
import caja.comun.ORDEN_DE_COBRO
import caja.comun.Observacion
import caja.comun.Registros
import caja.comun.Transaccion
import caja.comun.campo
import caja.comun.sinCamposDesconocidos
import caja.modelo.ANULADA
import caja.modelo.OrdenDeCobro
import caja.modelo.PAGADA
import caja.modelo.PENDIENTE
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

// las órdenes de cobro: el alta que manda el sistema de origen y la lista de la ventanilla. los permisos los aplica
// RecordService como el usuario que llama: un CAJERO no da de alta (403 de core). el sistema de origen es la cuenta de
// servicio que llama (su nombre), no lo que diga el cuerpo; un ADMIN lo nombra en el cuerpo, de contingencia; cualquier
// otra persona, aunque tenga el rol SISTEMA_ORIGEN, recibe 403.
// el alta es idempotente por (sistema_origen, referencia_externa) y la garantía es del motor, no de un if (#188 de caja):
// se inserta, y si la uniqueConstraint salta se relee la que ya estaba. una lectura previa se colaría por la carrera: dos altas
// simultáneas pasarían las dos la comprobación, y el mismo administrado tendría dos órdenes por la misma deuda.
// idempotente quiere decir el mismo pedido: si la que ya estaba es otra deuda (otro importe, otra fecha de exigibilidad u
// otro pagador, diferencias de Choque.kt), es un 409 que nombra los campos (caja-backend#40). un 200 con la vieja le
// haría creer al origen que caja cobrará lo que acaba de mandar
//
// la sustitución (caja-backend#40): un origen que redetermina manda la versión nueva con otra referencia y nombra la
// vieja en sustituye_a. la nueva nace y la vieja queda ANULADA en una sola transacción, bajo el candado de la vieja: o
// pasan las dos cosas o ninguna, y un cobro de la vieja a la vez se ordena con ella como con el retiro
//
// el retiro (caja-backend#39): el sistema de origen retira una orden que ya no se debe, y queda ANULADA. la misma regla
// del sistema que el alta: una cuenta solo retira lo suyo, y una orden de otro sistema no la encuentra (404, como si no
// existiera). la decisión se toma bajo el candado de la orden, el mismo que toma el cobro: un retiro y un cobro a la vez
// se ordenan, y el que llega segundo ve lo que dejó el primero (una PAGADA no se retira, una ANULADA no se cobra)
@Service
class OrdenesService(
    private val registros: Registros,
    private val candados: Candados,
    private val transaccion: Transaccion,
    private val currentUser: CurrentUser,
    private val reloj: Clock
) {
    suspend fun registrar(body: NuevaOrden): OrdenRespuesta {
        val usuario = currentUser.require()
        val cuenta = usuario.serviceAccount
        if (cuenta == null && !usuario.isAdmin) {
            throw ForbiddenException("El alta de órdenes es de un sistema de origen (su cuenta de servicio); una persona no la da")
        }
        val atributos = atributos(body, cuenta)
        val vieja = atributos["sustituye_a"] as String?
        return if (vieja == null) alta(atributos) else sustitucion(atributos, vieja)
    }

    private suspend fun alta(atributos: Map<String, Any?>): OrdenRespuesta =
        try {
            OrdenRespuesta.de(crear(atributos), nueva = true)
        } catch (choque: DataIntegrityViolationException) {
            // DuplicateKeyException incluida. RecordService no abre transacción: la orden que ganó ya está confirmada
            // cuando el motor rechaza esta. si no está, el choque fue otro y sigue su camino
            val existente = reenvio(atributos) ?: throw choque
            // la que ya estaba la retiró el origen: un 200 le haría creer que caja la va a cobrar, y no la cobra nunca.
            // el reenvío de una sustitución (con sustituye_a) no pasa por aquí: sigue siendo idempotente
            if (existente.estado == ANULADA) throw yaRetirada(existente)
            existente
        }

    private fun yaRetirada(orden: OrdenRespuesta) =
        ConflictException(
            "La orden de cobro ${orden.sistemaOrigen}/${orden.referenciaExterna} ya está ANULADA: se retiró el " +
                "${orden.retiradaEn ?: "(sin fecha)"} con el motivo «${orden.motivoRetiro ?: "sin motivo"}», y caja no la cobra. " +
                "Una versión nueva de la deuda lleva otra referencia y nombra la anterior en sustituye_a"
        )

    // 201 con la nueva y la vieja ANULADA con sustituida_por; 200 si la nueva ya estaba y pide lo mismo (el reenvío);
    // 409 si la vieja está PAGADA o ya la sustituyó otra, o si la nueva ya estaba con otra deuda; 404 si la vieja no
    // es de este sistema o no existe. en todos los errores no nace nada
    private suspend fun sustitucion(
        atributos: Map<String, Any?>,
        vieja: String
    ): OrdenRespuesta {
        // el reenvío se reconoce fuera de la transacción, por la referencia nueva: la sustitución ya pasó entera
        reenvio(atributos)?.let { return it }
        val sistema = atributos.getValue("sistema_origen") as String
        val anterior =
            porOrigen(sistema, vieja)
                ?: throw NotFoundException("sustituye_a: no hay ninguna orden de cobro de $sistema con la referencia $vieja")
        val creada =
            try {
                transaccion.en { sustituirBajoCandado(anterior, atributos, OffsetDateTime.now(reloj)) }
            } catch (choque: DataIntegrityViolationException) {
                // dos sustituciones iguales a la vez: la segunda esperó el candado y su alta chocó con la nueva que
                // confirmó la primera. un unique que salta aborta la transacción entera (25P02 a todo lo que siga), así
                // que se resuelve fuera, ya revertida, como un reenvío
                return reenvio(atributos) ?: throw choque
            }
        // null: la vieja ya la sustituyó esta misma nueva, que se confirmó mientras se esperaba el candado
        return creada?.let { OrdenRespuesta.de(it, nueva = true) } ?: reenvio(atributos) ?: error("La sustitución de $vieja se perdió")
    }

    // dentro de la transacción: el candado de la vieja, la vieja releída y la decisión sobre lo que hay ahora. una
    // PENDIENTE se retira con el motivo «Sustituida por …»; una ANULADA sin sustituta (el origen la retiró antes) se deja
    // con su motivo. después nace la nueva y la vieja anota quién la sustituyó
    private suspend fun sustituirBajoCandado(
        anterior: OrdenDeCobro,
        atributos: Map<String, Any?>,
        ahora: OffsetDateTime
    ): OrdenDeCobro? {
        val id = anterior.id!!
        val nueva = atributos.getValue("referencia_externa") as String
        candados.bloquear(Candado.ORDEN, id)
        val actual = registros.get(ORDEN_DE_COBRO, OrdenDeCobro::class.java, UUID.fromString(id))
        val nombre = "${actual.sistemaOrigen}/${actual.referenciaExterna}"
        when {
            actual.estado == PAGADA ->
                throw ConflictException(
                    "La orden $nombre ya se cobró con el recibo ${actual.recibo}: no se sustituye y $nueva no se crea. El " +
                        "sistema de origen imputa ese pago cuando lo reciba. Si ese recibo se anula, la orden vuelve a PENDIENTE " +
                        "y se puede sustituir"
                )
            actual.sustituidaPor == nueva -> return null
            actual.sustituidaPor != null ->
                throw ConflictException(
                    "La orden $nombre ya la sustituyó ${actual.sustituidaPor}: una orden se sustituye una sola vez, y $nueva no " +
                        "se crea. La versión nueva sustituye a la última"
                )
            actual.estado == PENDIENTE -> retirarBajoCandado(actual, motivoDeSustitucion(nueva), ahora)
        }
        val creada = crear(atributos)
        registros.replace(ORDEN_DE_COBRO, OrdenDeCobro::class.java, UUID.fromString(id), mapOf("sustituida_por" to nueva), RAZON_SUSTITUCION)
        return creada
    }

    private suspend fun crear(atributos: Map<String, Any?>): OrdenDeCobro =
        registros.create(ORDEN_DE_COBRO, OrdenDeCobro::class.java, atributos, atributos.getValue("observacion") as String)

    // la que ya estaba con esa referencia, con 200, si pide lo mismo; si pide otra deuda, 409 con los campos que
    // difieren. null si no hay ninguna
    private suspend fun reenvio(atributos: Map<String, Any?>): OrdenRespuesta? {
        val existente = porOrigen(atributos) ?: return null
        val distintos = diferencias(existente, atributos)
        if (distintos.isNotEmpty()) {
            throw ConflictException(
                "Ya hay una orden de cobro ${existente.sistemaOrigen}/${existente.referenciaExterna} con esa referencia y " +
                    "otro ${distintos.joinToString(", ")}: caja no la cambia. Una versión nueva de la deuda lleva otra " +
                    "referencia y nombra la anterior en sustituye_a"
            )
        }
        return OrdenRespuesta.de(existente, nueva = false)
    }

    // 200 con la orden ANULADA, también si ya lo estaba (el reintento del origen): la primera vez manda, con su motivo y
    // su fecha. 409 si está PAGADA, 404 si no hay una de ese sistema con esa referencia
    suspend fun retirar(body: RetiroDeOrden): OrdenRespuesta {
        val usuario = currentUser.require()
        val cuenta = usuario.serviceAccount
        if (cuenta == null && !usuario.isAdmin) {
            throw ForbiddenException("El retiro de órdenes es de un sistema de origen (su cuenta de servicio); una persona no lo hace")
        }
        val errores = mutableListOf<FieldViolation>()
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "un retiro") }
        val sistema =
            if (cuenta == null) {
                campo(errores) { sistemaOrigen(body.sistemaOrigen) }
            } else {
                campo(errores) { sistemaDeLaCuenta(cuenta, body.sistemaOrigen, "retira") }
            }
        val referencia = campo(errores) { referenciaExterna(body.referenciaExterna) }
        val motivo = campo(errores) { motivoDeRetiro(body.motivo) }
        if (errores.isNotEmpty()) throw ValidationException("El retiro no es válido", errores)

        // la referencia no cambia nunca: se busca antes del candado, y bajo él se relee la orden por su id
        val orden =
            porOrigen(sistema!!, referencia!!)
                ?: throw NotFoundException("No hay ninguna orden de cobro de $sistema con la referencia $referencia")
        return OrdenRespuesta.de(transaccion.en { retirarBajoCandado(orden, motivo!!, OffsetDateTime.now(reloj)) })
    }

    // dentro de una transacción: toma el candado de la orden, la relee y decide sobre lo que hay ahora. PENDIENTE pasa a
    // ANULADA; ANULADA se devuelve como está; PAGADA es un 409 que nombra su recibo. la escribe quien llama, por la api de
    // caja (Registros, con EscrituraDeCaja): la plataforma no escribe dentro de una petición (asPlatform de wasichai lo
    // rechaza), y la API genérica no escribe orden_de_cobro (apiOnly), así que el UPDATE de SISTEMA_ORIGEN solo sirve aquí
    internal suspend fun retirarBajoCandado(
        orden: OrdenDeCobro,
        motivo: String,
        ahora: OffsetDateTime
    ): OrdenDeCobro {
        val id = orden.id!!
        candados.bloquear(Candado.ORDEN, id)
        val actual = registros.get(ORDEN_DE_COBRO, OrdenDeCobro::class.java, UUID.fromString(id))
        return when (actual.estado) {
            ANULADA -> actual
            PAGADA ->
                throw ConflictException(
                    "La orden ${actual.sistemaOrigen}/${actual.referenciaExterna} ya se cobró con el recibo ${actual.recibo}: el " +
                        "sistema de origen la imputa cuando reciba el pago. Si ese recibo se anula, la orden vuelve a PENDIENTE y " +
                        "se puede retirar"
                )
            else ->
                registros.replace(
                    ORDEN_DE_COBRO,
                    OrdenDeCobro::class.java,
                    UUID.fromString(id),
                    mapOf("estado" to ANULADA, "motivo_retiro" to motivo, "retirada_en" to ahora.toString()),
                    RAZON_RETIRO
                )
        }
    }

    suspend fun listar(
        pagadorDocumento: String?,
        estado: String?,
        page: Int?,
        size: Int?
    ): PageResponse<OrdenRespuesta> {
        val filtros =
            buildMap {
                put("estado", estadoOrden(estado))
                pagadorDocumento?.trim()?.ifEmpty { null }?.let { put("pagador_documento", it.uppercase(Locale.ROOT)) }
            }
        val ordenes =
            registros.page(
                ORDEN_DE_COBRO,
                OrdenDeCobro::class.java,
                RecordQuery(page = PageRequest.of(page, size), sort = "fecha_exigibilidad", filters = filtros)
            )
        return PageResponse(ordenes.content.map { OrdenRespuesta.de(it) }, ordenes.page, ordenes.size, ordenes.totalElements, ordenes.totalPages)
    }

    private suspend fun porOrigen(atributos: Map<String, Any?>): OrdenDeCobro? =
        porOrigen(atributos.getValue("sistema_origen") as String, atributos.getValue("referencia_externa") as String)

    private suspend fun porOrigen(
        sistema: String,
        referencia: String
    ): OrdenDeCobro? = registros.primero(ORDEN_DE_COBRO, OrdenDeCobro::class.java, mapOf("sistema_origen" to sistema, "referencia_externa" to referencia))

    // las reglas sobre el cuerpo, con todos los campos que fallan en un solo 400
    private fun atributos(
        body: NuevaOrden,
        cuenta: String?
    ): Map<String, Any?> {
        val errores = mutableListOf<FieldViolation>()
        // la frontera se defiende en la entrada: una orden no lleva tributo, ejercicio ni periodo, ni nada que la caja no
        // conozca. callarlo dejaría creer al sistema de origen que la caja lo guardó
        campo(errores) { sinCamposDesconocidos(body.desconocidos, "una orden de cobro") }
        val sistema =
            if (cuenta == null) {
                campo(errores) { sistemaOrigen(body.sistemaOrigen) }
            } else {
                campo(errores) { sistemaDeLaCuenta(cuenta, body.sistemaOrigen, "da de alta") }
            }
        val referencia = campo(errores) { referenciaExterna(body.referenciaExterna) }
        val concepto = campo(errores) { concepto(body.concepto) }
        val detalle = campo(errores) { detalle(body.detalle) }
        val importe = campo(errores) { importe(body.importe) }
        val exigible = campo(errores) { fecha(body.fechaExigibilidad, "fecha_exigibilidad") }
        val actualizado = campo(errores) { fecha(body.actualizadoA, "actualizado_a") }
        val pagador = campo(errores) { pagador(body.pagadorDocumento, body.pagadorNombre, body.pagadorExternoId) }
        val observacion = campo(errores) { Observacion.de(body.observacion) }
        val vieja = campo(errores) { sustituyeA(body.sustituyeA, referencia) }
        if (errores.isNotEmpty()) throw ValidationException("La orden de cobro no es válida", errores)

        val atributos =
            mapOf(
                "sistema_origen" to sistema!!,
                "referencia_externa" to referencia!!,
                "concepto" to concepto,
                "detalle" to detalle,
                "importe" to importe!!.toPlainString(),
                "fecha_exigibilidad" to exigible.toString(),
                "actualizado_a" to actualizado.toString(),
                "pagador_documento" to pagador!!.documento,
                "pagador_nombre" to pagador.nombre,
                "pagador_externo_id" to pagador.idExterno,
                "estado" to PENDIENTE,
                "observacion" to observacion!!.texto
            )
        // sustituye_a solo va si viene: diferencias la compara cuando la pedida la trae, y un alta sin ella que repite
        // la referencia de una nueva no es otro pedido
        return if (vieja == null) atributos else atributos + ("sustituye_a" to vieja)
    }

    // el sistema de una cuenta de servicio es su nombre, que tiene que cumplir la regla de sistema_origen. el cuerpo puede
    // omitirlo; si lo trae, normalizado con la misma regla, tiene que ser el de la cuenta: una cuenta no da de alta a
    // nombre de otro sistema, ni retira las suyas (403, no 400: no es un campo mal escrito, es un permiso)
    private fun sistemaDeLaCuenta(
        cuenta: String,
        enElCuerpo: String?,
        que: String
    ): String {
        val sistema =
            try {
                sistemaOrigen(cuenta)
            } catch (_: ValidationException) {
                throw ForbiddenException(
                    "La cuenta de servicio $cuenta no es un sistema de origen válido: debe cumplir [a-z0-9_-], de 1 a $LARGO_SISTEMA caracteres"
                )
            }
        if (enElCuerpo != null && sistemaOrigen(enElCuerpo) != sistema) {
            throw ForbiddenException("La cuenta de servicio $cuenta solo $que órdenes de su sistema: $sistema")
        }
        return sistema
    }
}
