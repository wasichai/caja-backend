package caja

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// la app misma (CajaApplication) sobre PostgreSQL plano: los módulos que instala responden, los que deja fuera no, lo
// que es de caja exige token y la forma del modelo que usará caja (enum, texto único, decimal y relación obligatoria)
// funciona de punta a punta
@TestPropertySource(properties = ["caja.municipalidad.nombre=${CajaApiTest.MUNICIPALIDAD}"])
class CajaSmokeTest : WasichaiIntegrationTest() {
    @Test
    fun `health is up`() {
        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("UP")
    }

    @Test
    fun `modulos instalados responden, los que se dejan fuera no`() {
        val token = bearer()
        val name = uniqueName("objeto")
        createObject(token, name, listOf(mapOf("name" to "codigo", "label" to "Codigo", "type" to "TEXT")))
        listOf("/api/objects/$name/views", "/api/objects/$name/forms", "/api/pages").forEach { path ->
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
        }
        // workflow, documents, gis y automatización no se instalan: sus rutas no existen
        listOf(
            "/api/objects/$name/document-types",
            "/api/objects/$name/records/${UUID.randomUUID()}/transitions",
            "/api/gis/layers",
            "/api/automation-runs",
            "/api/agent/status"
        ).forEach { path ->
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isNotFound
        }
    }

    @Test
    fun `una ruta de caja sin token da 401`() {
        client
            .get()
            .uri("/api/caja/cajas")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `un recibo apunta a su titular por una relación obligatoria`() {
        val token = bearer()
        val titular = uniqueName("titular")
        val recibo = uniqueName("recibo")
        createObject(
            token,
            titular,
            listOf(
                mapOf("name" to "codigo", "label" to "Codigo", "type" to "TEXT", "required" to true, "unique" to true),
                mapOf("name" to "tipo", "label" to "Tipo", "type" to "ENUM", "enumOptions" to listOf("A", "B"))
            )
        )
        createObject(token, recibo, listOf(mapOf("name" to "importe", "label" to "Importe", "type" to "DECIMAL")))
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to "${recibo}_c",
                    "label" to "Titular",
                    "inverseLabel" to "Recibos",
                    "type" to "MANY_TO_ONE",
                    "source" to recibo,
                    "target" to titular,
                    "fieldName" to "titular"
                )
            ).exchange()
            .expectStatus()
            .isCreated
        // la relación se vuelve obligatoria con un PUT sobre el campo, como lo hará la carga del modelo
        client
            .put()
            .uri("/api/metadata/objects/$recibo/fields/titular")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("required" to true))
            .exchange()
            .expectStatus()
            .isOk

        val titularId = createRecord(token, titular, mapOf("codigo" to "C-0001", "tipo" to "A"))
        client
            .post()
            .uri("/api/objects/$recibo/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("importe" to "10080.45")))
            .exchange()
            .expectStatus()
            .isBadRequest
        val reciboId = createRecord(token, recibo, mapOf("importe" to "10080.45", "titular" to titularId))
        client
            .get()
            .uri("/api/objects/$recibo/records/$reciboId")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.titular")
            .isEqualTo(titularId)
            .jsonPath("$.attributes.importe")
            .isEqualTo(10080.45)
    }

    private fun createObject(
        token: String,
        name: String,
        fields: List<Map<String, Any>>
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to name, "pluralLabel" to name, "fields" to fields))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        token: String,
        objectName: String,
        attributes: Map<String, Any>
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!["id"] as String
}
