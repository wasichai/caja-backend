package caja.turno

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import wasichai.core.common.GlobalExceptionHandler

// el 409 de los pagos sin entregar lleva, además del detail, la lista: pagos_sin_entregar con el pago_id, el tipo y el
// estado de cada uno, para que la ventanilla diga cuáles sin tener que leer el texto. el resto del problem es el de
// core (su type, su title y su status): se arma con su manejador, y este va antes que él
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProblemasDelTurno(
    private val core: GlobalExceptionHandler
) {
    @ExceptionHandler(HayPagosSinEntregar::class)
    fun pagosSinEntregar(ex: HayPagosSinEntregar): ProblemDetail = core.handleWasichai(ex).apply { setProperty("pagos_sin_entregar", ex.pagos) }
}
