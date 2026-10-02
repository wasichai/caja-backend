package caja.recaudacion

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// el avance de recaudación y la recaudación por área (RecaudacionController de caja, RF-088, RF-089). dos GET que no
// hacen esperar a la ventanilla, sin paginar a propósito: son agregados, y el total de una página no es el del periodo
@RestController
@RequestMapping("/api/caja/recaudacion")
class RecaudacionController(
    private val consulta: ConsultaDeRecaudacion
) {
    @GetMapping("/avance")
    suspend fun avance(
        @RequestParam(required = false) desde: String?,
        @RequestParam(required = false) hasta: String?,
        @RequestParam(required = false) origen: String?,
        @RequestParam(required = false) caja: String?,
        @RequestParam(required = false) cajero: String?
    ) = consulta.avance(desde, hasta, origen, caja, cajero)

    @GetMapping("/por-area")
    suspend fun porArea(
        @RequestParam(required = false) area: String?,
        @RequestParam(required = false) desde: String?,
        @RequestParam(required = false) hasta: String?
    ) = consulta.porArea(area, desde, hasta)
}

// la conciliación del día (ConciliacionController de caja, ADR-0026 §3). la fecha es obligatoria, y la regla la exige
// (400 en fecha) en vez de dejarlo a Spring, para que el 400 nombre su campo
@RestController
@RequestMapping("/api/caja/conciliacion")
class ConciliacionController(
    private val conciliacion: ConciliacionDelDia
) {
    @GetMapping
    suspend fun del(
        @RequestParam(required = false) fecha: String?
    ) = conciliacion.de(fecha)
}
