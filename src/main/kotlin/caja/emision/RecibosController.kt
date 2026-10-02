package caja.emision

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

// el papel del recibo, bajo /api/caja como el resto de la api de caja: el token de core la protege y su manejador de
// errores contesta problem+json
@RestController
@RequestMapping("/api/caja/recibos")
class RecibosController(
    private val original: OriginalDelRecibo
) {
    @GetMapping("/{numero}/pdf")
    suspend fun pdf(
        @PathVariable numero: String
    ): ResponseEntity<ByteArray> {
        val pdf = original.pdf(numero)
        // el número ya nombra un recibo guardado: es seguro como nombre de archivo
        val archivo = ContentDisposition.inline().filename("recibo-${numero.trim().uppercase(Locale.ROOT)}.pdf").build()
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, archivo.toString())
            .body(pdf)
    }
}
