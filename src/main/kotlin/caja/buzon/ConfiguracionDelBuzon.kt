package caja.buzon

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration

// caja.buzon en application.yml. EL DESTINO ES CONFIGURABLE Y ESTÁ APAGADO POR DEFECTO (decisión 5 del plan, como PIDE
// en srtm): con habilitado=false no arranca ningún bucle, los pagos quedan PENDIENTE y el turno que los tiene no cierra
// (la regla del PR 7). cada sistema de origen es una entrada de destinos, por su nombre (el sistema_origen de sus
// órdenes): uno nuevo es una línea de configuración, no un despliegue. el token, si hay, viaja como Authorization:
// Bearer y nunca en el cuerpo
@ConfigurationProperties("caja.buzon")
data class PropiedadesDelBuzon(
    val habilitado: Boolean = false,
    // la espera entre el final de una vuelta y el comienzo de la siguiente (fixedDelay, no fixedRate: una vuelta lenta no
    // se solapa con la siguiente)
    val intervalo: Duration = Duration.ofSeconds(10),
    // cuántos eventos PENDIENTE lee por organización en cada vuelta: una vuelta tiene que acabar
    val porVuelta: Int = 50,
    // con cuántos intentos fallidos un pago que no contesta pasa a MUERTO
    val intentos: Int = 8,
    // cuánto espera la respuesta de un destino (y su conexión)
    val timeout: Duration = Duration.ofSeconds(10),
    val destinos: Map<String, Destino> = emptyMap()
) {
    init {
        require(intentos >= 1) { "caja.buzon.intentos es al menos 1: con cero, todo pago nacería muerto" }
        require(porVuelta >= 1) { "caja.buzon.por-vuelta es al menos 1" }
        require(!intervalo.isNegative && !intervalo.isZero) { "caja.buzon.intervalo es una espera positiva" }
    }

    data class Destino(
        val url: String? = null,
        val token: String? = null
    ) {
        // el token nunca sale en un toString (un registro de arranque, un volcado de la configuración)
        override fun toString() = "Destino(url=$url, token=${if (token.isNullOrBlank()) "ninguno" else "«…»"})"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropiedadesDelBuzon::class)
class ConfiguracionDelBuzon

// a quién se avisa cuando hay dinero cobrado sin registrar (ResponsableDeLaConciliacion de caja, ADR-0026 §4): una
// persona con nombre y un canal, caja.conciliacion.responsable y .canal. con el buzón encendido son OBLIGATORIOS y se
// comprueban al arrancar: una alerta sin destinatario acaba en un panel que nadie mira, y una propiedad opcional se
// queda vacía justo en la instalación donde importa. con el buzón apagado no muere ningún pago y la app arranca sin ellos
@Component
class ResponsableDeLaConciliacion(
    buzon: PropiedadesDelBuzon,
    @Value("\${caja.conciliacion.responsable:}") nombre: String,
    @Value("\${caja.conciliacion.canal:}") canal: String
) {
    val nombre: String = nombre.trim()
    val canal: String = canal.trim()

    init {
        if (buzon.habilitado) {
            val faltan = listOf("caja.conciliacion.responsable" to this.nombre, "caja.conciliacion.canal" to this.canal).filter { it.second.isEmpty() }
            check(faltan.isEmpty()) {
                "Con caja.buzon.habilitado=true faltan ${faltan.joinToString(" y ") { it.first }}: un pago que no se pudo entregar es " +
                    "dinero cobrado sin registrar y avisa a una persona con nombre, por un canal. La caja no arranca hasta que se " +
                    "diga quién recibe la alerta (CAJA_CONCILIACION_RESPONSABLE y CAJA_CONCILIACION_CANAL)"
            }
        }
    }

    override fun toString() = "$nombre <$canal>"
}
