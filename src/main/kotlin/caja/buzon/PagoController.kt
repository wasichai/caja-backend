package caja.buzon

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// los pagos que no se pudieron entregar y su explicación (PagoController de caja). bajo /api/caja como el resto: el
// token de core la protege y su manejador contesta problem+json
@RestController
@RequestMapping("/api/caja/pagos")
class PagoController(
    private val sinEntregar: PagosSinEntregar,
    private val explicar: ExplicarPagoSinEntregar
) {
    // los MUERTO: dinero cobrado sin registrar, lo que hay que resolver hoy. ruta propia y no un filtro de un listado
    @GetMapping("/sin-entregar")
    suspend fun sinEntregar() = sinEntregar.listar()

    // 200: cambia el estado de un evento que ya existe, no crea nada
    @PostMapping("/{pagoId}/explicacion")
    suspend fun explicacion(
        @PathVariable pagoId: String,
        @RequestBody body: PeticionDeExplicacion
    ) = explicar.explicar(pagoId, body)
}
