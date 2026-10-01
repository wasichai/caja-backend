package caja.emision

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

// la municipalidad que encabeza el recibo: caja.municipalidad.nombre (CAJA_MUNICIPALIDAD_NOMBRE). obligatoria: sin
// ella la app no arranca, porque un recibo sin quién lo emite no es un recibo
@Component
class Municipalidad(
    @Value("\${caja.municipalidad.nombre:}") nombre: String
) {
    val nombre: String =
        nombre.trim().also {
            check(it.isNotEmpty()) { "Falta caja.municipalidad.nombre (CAJA_MUNICIPALIDAD_NOMBRE): el nombre que encabeza el recibo" }
        }
}
