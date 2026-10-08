package caja.comun

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// la hora de caja es la de Lima, no la de la máquina
val LIMA: ZoneId = ZoneId.of("America/Lima")

// un instante como lo publica la api de caja: en ISO, con el desfase de Lima (2026-10-02T10:15:30.123-05:00)
fun enLima(instante: Instant): String = instante.atZone(LIMA).toOffsetDateTime().toString()

// el reloj de caja. una prueba lo sustituye con @MockitoBean, o con un @Bean @Primary propio (Clock.fixed): nada de
// caja lee la hora de otro sitio
@Configuration(proxyBeanMethods = false)
class Relojes {
    @Bean
    fun reloj(): Clock = Clock.system(LIMA)
}
