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

// la api de caja. bajo /api como las rutas de core: la cadena jwt de core la protege y su manejador de errores contesta
// problem+json. los handlers son suspend: el contexto de seguridad del que llama vive en la corrutina de la petición, y
// RecordService lo lee para aplicar sus permisos
@RestController
@RequestMapping("/api/caja")
class CobroController(
    private val ordenes: OrdenesService,
    private val cajas: CajasService,
    private val cobros: CobroService
) {
    // la llama el sistema de origen, servidor a servidor. 201 si la orden se creó, 200 si ya estaba: un reintento
    // tiene derecho a saber si se reconoció o si acaba de crear otra
    @PostMapping("/ordenes-de-cobro")
    suspend fun registrar(
        @RequestBody body: NuevaOrden
    ): ResponseEntity<OrdenRespuesta> {
        val alta = ordenes.registrar(body)
        return ResponseEntity.status(if (alta.nueva == true) HttpStatus.CREATED else HttpStatus.OK).body(alta)
    }

    // la ventanilla: las órdenes de un pagador. sin estado, las pendientes
    @GetMapping("/ordenes-de-cobro")
    suspend fun ordenes(
        @RequestParam(name = "pagador_documento", required = false) pagadorDocumento: String?,
        @RequestParam(required = false) estado: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ) = ordenes.listar(pagadorDocumento, estado, page, size)

    // la ventanilla cobra: 201 con el recibo emitido. el reenvío con la misma Idempotency-Key contesta 200 con el recibo
    // de la primera vez y emitido false: el cliente sabe que su reintento se reconoció y que no se cobró otra vez
    @PostMapping("/cobros")
    suspend fun cobrar(
        @RequestBody body: NuevoCobro,
        @RequestHeader(name = "Idempotency-Key", required = false) clave: String?
    ): ResponseEntity<CobroRespuesta> {
        val cobro = cobros.cobrar(body, clave)
        return ResponseEntity.status(if (cobro.emitido) HttpStatus.CREATED else HttpStatus.OK).body(cobro)
    }

    @GetMapping("/cajas")
    suspend fun cajas(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ) = cajas.listar(page, size)
}
