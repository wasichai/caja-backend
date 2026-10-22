package caja.buzon

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.time.Instant

// el destino con cuenta de servicio (caja-backend#50): pide su token a {token-url}, lo reusa hasta un minuto antes de
// que venza, lo olvida cuando el origen lo rechaza, y no arranca si además tiene un token fijo
class TokenDelDestinoTest {
    private val origen = SistemaDeOrigenFalso()
    private var ahora: Instant = Instant.parse("2026-10-22T12:00:00Z")

    @AfterEach
    fun cerrar() = origen.close()

    private fun cliente(
        token: String? = null,
        clientId: String? = "caja",
        secreto: String = "s3creto-de-caja"
    ): ClienteDelSistemaDeOrigen {
        origen.expiraEn = ahora.plus(Duration.ofMinutes(15))
        val destino =
            PropiedadesDelBuzon.Destino(
                url = origen.url(),
                token = token,
                tokenUrl = origen.url() + "/api/auth/token",
                clientId = clientId,
                clientSecret = secreto
            )
        return ClienteDelSistemaDeOrigen(PropiedadesDelBuzon(destinos = mapOf("rentas" to destino))).also { it.reloj = { ahora } }
    }

    private fun publicar(
        cliente: ClienteDelSistemaDeOrigen,
        pagoId: String
    ): Respuesta = runBlocking { cliente.publicar("rentas", """{"pagoId":"$pagoId"}""") }

    @Test
    fun `pide el token una vez y lo reusa`() {
        val cliente = cliente()
        origen.contestar("p1", 202)
        origen.contestar("p2", 202)
        assertTrue(publicar(cliente, "p1") is Respuesta.Entregado)
        assertTrue(publicar(cliente, "p2") is Respuesta.Entregado)
        assertEquals(1, origen.pedidosDeToken.size)
        assertTrue(origen.pedidosDeToken.single().contains(""""clientId":"caja""""), origen.pedidosDeToken.single())
        assertEquals("Bearer tok-1", origen.de("p1").single().autorizacion)
        assertEquals("Bearer tok-1", origen.de("p2").single().autorizacion)
    }

    @Test
    fun `renueva al vencer`() {
        val cliente = cliente()
        origen.contestar("p1", 202)
        origen.contestar("p2", 202)
        publicar(cliente, "p1")
        ahora = ahora.plus(Duration.ofMinutes(14)).plusSeconds(1)
        origen.expiraEn = ahora.plus(Duration.ofMinutes(15))
        publicar(cliente, "p2")
        assertEquals(2, origen.pedidosDeToken.size)
        assertEquals("Bearer tok-2", origen.de("p2").single().autorizacion)
    }

    @Test
    fun `un 401 olvida el token y la siguiente entrega pide uno nuevo`() {
        val cliente = cliente()
        origen.contestar("p1", 401)
        origen.contestar("p2", 202)
        assertInstanceOf(Respuesta.NoContesta::class.java, publicar(cliente, "p1"))
        assertEquals(1, origen.pedidosDeToken.size)
        assertTrue(publicar(cliente, "p2") is Respuesta.Entregado)
        assertEquals(2, origen.pedidosDeToken.size)
        assertEquals("Bearer tok-2", origen.de("p2").single().autorizacion)
    }

    @Test
    fun `un 403 en una lectura tambien lo olvida`() {
        val cliente = cliente()
        origen.conciliar(java.time.LocalDate.parse("2026-10-21"), 403, "")
        val lectura = runBlocking { cliente.leer("rentas", "/pagos/conciliacion?fecha=2026-10-21") }
        assertEquals(403, assertInstanceOf(Lectura.Contesto::class.java, lectura).estado)
        runBlocking { cliente.leer("rentas", "/pagos/conciliacion?fecha=2026-10-21") }
        assertEquals(2, origen.pedidosDeToken.size)
    }

    @Test
    fun `sin client-id la cabecera es la del token fijo`() {
        val cliente = cliente(token = "fijo-123", clientId = null)
        origen.contestar("p1", 202)
        publicar(cliente, "p1")
        assertEquals("Bearer fijo-123", origen.de("p1").single().autorizacion)
        assertTrue(origen.pedidosDeToken.isEmpty())
    }

    @Test
    fun `si el origen no da token la entrega no contesta y el diagnostico no lleva el secreto`() {
        val cliente = cliente()
        origen.estadoDelToken = 500
        origen.contestar("p1", 202)
        val respuesta = assertInstanceOf(Respuesta.NoContesta::class.java, publicar(cliente, "p1"))
        assertTrue(respuesta.motivo.contains("caja"), respuesta.motivo)
        assertTrue(respuesta.motivo.contains("500"), respuesta.motivo)
        assertFalse(respuesta.motivo.contains("s3creto-de-caja"), respuesta.motivo)
        assertTrue(origen.recibidas.isEmpty())
    }

    @Test
    fun `el toString del destino oculta el secreto`() {
        val destino = PropiedadesDelBuzon.Destino(tokenUrl = "http://x/t", clientId = "caja", clientSecret = "s3creto-de-caja")
        assertFalse(destino.toString().contains("s3creto-de-caja"))
        assertTrue(destino.toString().contains("caja"))
    }

    @Test
    fun `los dos modos no arrancan`() {
        val runner = ApplicationContextRunner().withUserConfiguration(ConfiguracionDelBuzon::class.java)
        runner
            .withPropertyValues(
                "caja.buzon.destinos.rentas.url=http://rentas",
                "caja.buzon.destinos.rentas.token=fijo",
                "caja.buzon.destinos.rentas.client-id=caja",
                "caja.buzon.destinos.rentas.client-secret=x",
                "caja.buzon.destinos.rentas.token-url=http://rentas/api/auth/token"
            ).run { contexto ->
                val mensaje = generateSequence(contexto.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                assertNotNull(contexto.startupFailure)
                assertTrue(mensaje.contains("token o con client-id"), mensaje)
            }
        runner
            .withPropertyValues(
                "caja.buzon.destinos.rentas.client-id=caja",
                "caja.buzon.destinos.rentas.client-secret=x",
                "caja.buzon.destinos.rentas.token-url=http://rentas/api/auth/token"
            ).run { assertEquals(null, it.startupFailure) }
    }
}
