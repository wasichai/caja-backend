package caja.cobro

import caja.comun.ORDEN_DE_COBRO
import caja.comun.Observacion
import caja.comun.Registros
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import wasichai.core.common.FieldViolation
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import java.util.Locale

// las órdenes de cobro: el alta que manda el sistema de origen y la lista de la ventanilla. los permisos los aplica
// RecordService como el usuario que llama: un CAJERO no da de alta (403 de core), un SISTEMA_ORIGEN sí.
// el alta es idempotente por (sistema_origen, referencia_externa) y la garantía es del motor, no de un if (#188 de caja):
// se inserta, y si la uniqueConstraint salta se relee la que ya estaba. una lectura previa se colaría por la carrera: dos altas
// simultáneas pasarían las dos la comprobación, y el mismo administrado tendría dos órdenes por la misma deuda
@Service
class OrdenesService(
    private val registros: Registros
) {
    suspend fun registrar(body: NuevaOrden): OrdenRespuesta {
        val atributos = atributos(body)
        return try {
            OrdenRespuesta.de(registros.create(ORDEN_DE_COBRO, OrdenDeCobro::class.java, atributos), nueva = true)
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
    private fun atributos(body: NuevaOrden): Map<String, Any?> {
        val errores = mutableListOf<FieldViolation>()

        fun <T> campo(regla: () -> T): T? =
            try {
                regla()
            } catch (e: ValidationException) {
                errores += e.violations
                null
            }

        campo { sinCamposDesconocidos(body.desconocidos) }
        val sistema = campo { sistemaOrigen(body.sistemaOrigen) }
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
}
