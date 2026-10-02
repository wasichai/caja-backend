package caja.recibo

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// el recibo después de emitido: el listado, la ficha, el duplicado en pdf y la anulación del mismo día (ReciboController
// de caja). bajo /api/caja como el resto: el token de core la protege y su manejador contesta problem+json
@RestController
@RequestMapping("/api/caja/recibos")
class ReciboController(
    private val consulta: ConsultaDeRecibos,
    private val duplicados: DuplicadoDeRecibo,
    private val anulaciones: AnularRecibo
) {
    // para quien perdió el papel: por documento del pagador, caja, cajero, días de Lima y estado
    @GetMapping
    suspend fun listar(
        @RequestParam(required = false) documento: String?,
        @RequestParam(required = false) caja: String?,
        @RequestParam(required = false) cajero: String?,
        @RequestParam(required = false) desde: String?,
        @RequestParam(required = false) hasta: String?,
        @RequestParam(required = false) estado: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ) = consulta.listar(documento, caja, cajero, desde, hasta, estado, page, size)

    @GetMapping("/{numero}")
    suspend fun ficha(
        @PathVariable numero: String
    ) = consulta.ficha(numero)

    // el duplicado escribe (cada reimpresión queda registrada): POST, 201 con el pdf
    @PostMapping("/{numero}/duplicados")
    suspend fun duplicado(
        @PathVariable numero: String,
        @RequestBody body: PeticionDeDuplicado
    ): ResponseEntity<ByteArray> {
        val duplicado = duplicados.imprimir(numero, body)
        // el número ya nombra un recibo guardado: es seguro como nombre de archivo
        val archivo = ContentDisposition.inline().filename("recibo-${duplicado.numeroImpreso}-duplicado-${duplicado.cual}.pdf").build()
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, archivo.toString())
            .body(duplicado.pdf)
    }

    @PostMapping("/{numero}/anulacion")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun anular(
        @PathVariable numero: String,
        @RequestBody body: PeticionDeAnulacion
    ) = anulaciones.anular(numero, body)
}
