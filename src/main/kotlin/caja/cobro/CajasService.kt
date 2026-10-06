package caja.cobro

import caja.comun.AREA
import caja.comun.CAJA
import caja.comun.Registros
import caja.modelo.Area
import caja.modelo.Caja
import org.springframework.stereotype.Service
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.data.RecordQuery

// el catálogo de ventanillas, por código, con el área de cada una: una consulta por página de cajas y una por sus áreas
@Service
class CajasService(
    private val registros: Registros
) {
    suspend fun listar(
        page: Int?,
        size: Int?
    ): PageResponse<CajaEnLista> {
        val cajas = registros.page(CAJA, Caja::class.java, RecordQuery(page = PageRequest.of(page, size), sort = "codigo"))
        val areas = registros.byIds(AREA, Area::class.java, cajas.content.mapNotNull { it.area })
        return PageResponse(
            cajas.content.map { caja ->
                val area = caja.area?.let(areas::get)
                CajaEnLista(caja.codigo, caja.nombre, caja.serie, area?.codigo, area?.nombre, caja.activa)
            },
            cajas.page,
            cajas.size,
            cajas.totalElements,
            cajas.totalPages
        )
    }
}
