package caja

import org.springframework.boot.runApplication
import wasichai.core.autoconfigure.WasichaiApplication

// el servidor entero: los starters de wasichai en el classpath hacen el resto.
// nunca bajo el paquete `wasichai`: el escaneo de componentes registraría dos veces los controladores de la librería (ADR-024)
@WasichaiApplication
class CajaApplication

fun main(args: Array<String>) {
    runApplication<CajaApplication>(*args)
}
