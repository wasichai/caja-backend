package caja.turno

import org.springframework.http.HttpStatus
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// el turno de la ventanilla, su arqueo, su cierre y su reversión (TurnoController, EstadoDelCierreController y
// CierreController de caja). bajo /api/caja como el resto: el token de core la protege y su manejador contesta
// problem+json. el cierre y la reversión solo se agregan: no hay ninguna ruta que modifique ni borre un cierre (regla 4)
@RestController
@RequestMapping("/api/caja/turnos")
class TurnoController(
    private val consulta: ConsultaDelTurno,
    private val cierres: CerrarTurno
) {
    // los turnos de hoy de quien pregunta. sin parámetros: cualquiera es un 400 que lo nombra
    @GetMapping("/del-dia")
    suspend fun delDia(
        @RequestParam parametros: MultiValueMap<String, String>
    ) = consulta.delDia(parametros.keys)

    // el arqueo en vivo y lo que impide cerrar
    @GetMapping("/{turnoId}/arqueo")
    suspend fun arqueo(
        @PathVariable turnoId: String
    ) = consulta.arqueo(turnoId)

    // 201: los dos actos crean un acta
    @PostMapping("/cierre")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun cerrar(
        @RequestBody body: PeticionDeCierre
    ) = cierres.cerrar(body)

    @PostMapping("/reversion")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun reversar(
        @RequestBody body: PeticionDeReversion
    ) = cierres.reversar(body)
}
