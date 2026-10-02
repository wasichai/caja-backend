package caja.cobro

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// la caja de tasas: la otra mitad de la ventanilla. vive bajo /cobros porque el dinero entra por la misma ventanilla,
// con la misma numeración y el mismo turno (CajaController de caja)
@RestController
@RequestMapping("/api/caja")
class TasasController(
    private val tasas: TasasService
) {
    // 201 con el recibo emitido; el reenvío con la misma Idempotency-Key, 200 con el recibo de la primera vez
    @PostMapping("/cobros/tasas")
    suspend fun cobrar(
        @RequestBody body: NuevoCobroDeTasas,
        @RequestHeader(name = "Idempotency-Key", required = false) clave: String?
    ): ResponseEntity<CobroRespuesta> {
        val cobro = tasas.cobrar(body, clave)
        return ResponseEntity.status(if (cobro.emitido) HttpStatus.CREATED else HttpStatus.OK).body(cobro)
    }

    // lo que costaría cobrar esos conceptos, sin cobrarlos: ningún total sale del cliente
    @PostMapping("/cobros/tasas/vista-previa")
    suspend fun vistaPrevia(
        @RequestBody body: VistaPreviaDeTasas
    ) = tasas.vistaPrevia(body)

    // las tasas vigentes a una fecha, por defecto hoy en Lima: la lista que ofrece la ventanilla
    @GetMapping("/tasas")
    suspend fun vigentes(
        @RequestParam(name = "vigentes_a", required = false) vigentesA: String?
    ) = tasas.vigentes(vigentesA)
}
