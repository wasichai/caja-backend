package caja.cobro

import caja.comun.ORDEN_DE_COBRO
import caja.comun.Observacion
import caja.comun.Registros
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import wasichai.core.identity.CurrentUser
import java.util.Locale

// las órdenes de cobro: el alta que manda el sistema de origen y la lista de la ventanilla. los permisos los aplica
// RecordService como el usuario que llama: un CAJERO no da de alta (403 de core). el sistema de origen es la cuenta de
// servicio que llama (su nombre), no lo que diga el cuerpo; un ADMIN lo nombra en el cuerpo, de contingencia; cualquier
// otra persona, aunque tenga el rol SISTEMA_ORIGEN, recibe 403.
// el alta es idempotente por (sistema_origen, referencia_externa) y la garantía es del motor, no de un if (#188 de caja):
// se inserta, y si la uniqueConstraint salta se relee la que ya estaba. una lectura previa se colaría por la carrera: dos altas
// simultáneas pasarían las dos la comprobación, y el mismo administrado tendría dos órdenes por la misma deuda
@Service
class OrdenesService(
    private val registros: Registros,
    private val currentUser: CurrentUser
) {
    suspend fun registrar(body: NuevaOrden): OrdenRespuesta {
        val usuario = currentUser.require()
        val cuenta = usuario.serviceAccount
        if (cuenta == null && !usuario.isAdmin) {
            throw ForbiddenException("El alta de órdenes es de un sistema de origen (su cuenta de servicio); una persona no la da")
        }
        val atributos = atributos(body, cuenta)
        return try {
            OrdenRespuesta.de(registros.create(ORDEN_DE_COBRO, OrdenDeCobro::class.java, atributos, atributos.getValue("observacion") as String), nueva = true)
        } catch (choque: DataIntegrityViolationException) {
            // DuplicateKeyException incluida. RecordService no abre transacción: la orden que ganó ya está confirmada
            // cuando el motor rechaza esta. si no está, el choque fue otro y sigue su camino
            OrdenRespuesta.de(porOrigen(atributos) ?: throw choque, nueva = false)
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
        registros.primero(
            ORDEN_DE_COBRO,
            OrdenDeCobro::class.java,
            mapOf(
                "sistema_origen" to atributos.getValue("sistema_origen") as String,
                "referencia_externa" to atributos.getValue("referencia_externa") as String
            )
        )

    // las reglas sobre el cuerpo, con todos los campos que fallan en un solo 400
    private fun atributos(
        body: NuevaOrden,
        cuenta: String?
    ): Map<String, Any?> {
        val errores = mutableListOf<FieldViolation>()

        fun <T> campo(regla: () -> T): T? =
            try {
                regla()
            } catch (e: ValidationException) {
                errores += e.violations
                null
            }

        campo { sinCamposDesconocidos(body.desconocidos) }
        val sistema = if (cuenta == null) campo { sistemaOrigen(body.sistemaOrigen) } else campo { sistemaDeLaCuenta(cuenta, body.sistemaOrigen) }
        val referencia = campo { referenciaExterna(body.referenciaExterna) }
        val concepto = campo { concepto(body.concepto) }
        val detalle = campo { detalle(body.detalle) }
        val importe = campo { importe(body.importe) }
        val exigible = campo { fecha(body.fechaExigibilidad, "fecha_exigibilidad") }
        val actualizado = campo { fecha(body.actualizadoA, "actualizado_a") }
        val pagador = campo { pagador(body.pagadorDocumento, body.pagadorNombre, body.pagadorExternoId) }
        val observacion = campo { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("La orden de cobro no es válida", errores)

        return mapOf(
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
    }

    // el sistema de una cuenta de servicio es su nombre, que tiene que cumplir la regla de sistema_origen. el cuerpo puede
    // omitirlo; si lo trae, normalizado con la misma regla, tiene que ser el de la cuenta: una cuenta no da de alta a
    // nombre de otro sistema (403, no 400: no es un campo mal escrito, es un permiso)
    private fun sistemaDeLaCuenta(
        cuenta: String,
        enElCuerpo: String?
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
            throw ForbiddenException("La cuenta de servicio $cuenta solo da de alta órdenes de su sistema: $sistema")
        }
        return sistema
    }
}
