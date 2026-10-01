package caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.UUID

// la base de las pruebas de integración de la api de caja (portada de SrtmApiTest): antes de cada prueba, el modelo
// real (model/model.json, aplicado como lo hace model/apply.py) y los roles de model/roles.json (como
// model/apply_roles.py), el token del admin sembrado y las llamadas que hacen las pruebas. la base la comparten todas
// las clases de la corrida: cada registro lleva claves únicas
abstract class CajaApiTest : WasichaiIntegrationTest() {
    // el del admin sembrado; una llamada toma otro donde la prueba lo necesita
    protected lateinit var token: String

    protected val json: JsonMapper get() = JSON

    @BeforeEach
    fun adminModeloYRoles() {
        token = bearer()
        applyModel()
        applyRoles()
    }

    protected fun modelo(): JsonNode = json.readTree(File("model/model.json"))

    // model/apply.py en kotlin: se crea lo que falta (objetos, campos, opciones de enum, relaciones obligatorias), un
    // campo que model.json ya no exige se relaja, lo demás se deja
    private fun applyModel() {
        val model = modelo()
        val enums = model["enums"]

        fun options(field: JsonNode) =
            enums[field["enum"].asString()]
                .iterator()
                .asSequence()
                .map { it.asString() }
                .toList()

        fun required(field: JsonNode) = field["required"]?.asBoolean() ?: false

        fun payload(field: JsonNode) =
            buildMap {
                put("name", field["name"].asString())
                put("label", field["label"].asString())
                put("type", field["type"].asString())
                put("required", required(field))
                put("unique", field["unique"]?.asBoolean() ?: false)
                if (field["type"].asString() == "ENUM") put("enumOptions", options(field))
            }

        val existing: Set<String> = tree(send("GET", "/api/objects", null, HttpStatus.OK)).names()
        for (obj in model["objects"]) {
            val name = obj["name"].asString()
            val fields = obj["fields"].iterator().asSequence().toList()
            if (name !in existing) {
                send(
                    "POST",
                    "/api/objects",
                    mapOf(
                        "name" to name,
                        "label" to obj["label"].asString(),
                        "pluralLabel" to obj["pluralLabel"].asString(),
                        "fields" to fields.map(::payload)
                    ),
                    HttpStatus.CREATED
                )
                continue
            }
            val stored =
                tree(
                    send("GET", "/api/metadata/objects/$name/fields", null, HttpStatus.OK)
                ).iterator().asSequence().associateBy { it["name"].asString() }
            for (field in fields) {
                val current = stored[field["name"].asString()]
                if (current == null) {
                    send("POST", "/api/metadata/objects/$name/fields", payload(field), HttpStatus.CREATED)
                    continue
                }
                val change = mutableMapOf<String, Any>()
                if (field["type"].asString() == "ENUM") {
                    val have =
                        current["enumOptions"]
                            .iterator()
                            .asSequence()
                            .map { it.asString() }
                            .toList()
                    val missing = options(field) - have.toSet()
                    if (missing.isNotEmpty()) change["enumOptions"] = have + missing
                }
                if (current["required"].asBoolean() && !required(field)) change["required"] = false
                if (change.isNotEmpty()) send("PUT", "/api/metadata/objects/$name/fields/${field["name"].asString()}", change, HttpStatus.OK)
            }
        }
        val relationships: Set<String> = tree(send("GET", "/api/relationships", null, HttpStatus.OK)).names()
        for (rel in model["relationships"]) {
            if (rel["name"].asString() in relationships) continue
            send(
                "POST",
                "/api/relationships",
                listOf("name", "label", "inverseLabel", "source", "target", "fieldName").associateWith { rel[it].asString() } +
                    ("type" to "MANY_TO_ONE"),
                HttpStatus.CREATED
            )
            if (rel["required"]?.asBoolean() == true) {
                send(
                    "PUT",
                    "/api/metadata/objects/${rel["source"].asString()}/fields/${rel["fieldName"].asString()}",
                    mapOf("required" to true),
                    HttpStatus.OK
                )
            }
        }
    }

    // model/apply_roles.py en kotlin: el rol que falta se crea, y cada uno queda con los permisos de roles.json
    private fun applyRoles() {
        val existing: Set<String> = tree(send("GET", "/api/roles", null, HttpStatus.OK)).names()
        for (rol in json.readTree(File("model/roles.json"))["roles"]) {
            val name = rol["name"].asString()
            if (name !in existing) send("POST", "/api/roles", mapOf("name" to name, "label" to rol["label"].asString()), HttpStatus.CREATED)
            val permisos =
                rol["permisos"].properties().flatMap { (objeto, acciones) ->
                    acciones
                        .iterator()
                        .asSequence()
                        .map { permiso(objeto, it.asString()) }
                        .toList()
                }
            send("PUT", "/api/roles/$name/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
        }
    }

    // llamadas

    // el cuerpo de una llamada que tiene que contestar `status`
    protected fun send(
        method: String,
        path: String,
        body: Any?,
        status: HttpStatus,
        token: String = this.token,
        cabeceras: Map<String, String> = emptyMap()
    ): String {
        val (actual, response) = exchange(method, path, body, token, cabeceras)
        assertEquals(status, actual, "$method $path: $response")
        return response
    }

    // estado y cuerpo, sin afirmar nada: se puede llamar fuera del hilo de la prueba
    protected fun exchange(
        method: String,
        path: String,
        body: Any?,
        token: String = this.token,
        cabeceras: Map<String, String> = emptyMap()
    ): Pair<HttpStatus, String> {
        val spec =
            when (method) {
                "GET" -> client.get().uri(path)
                "DELETE" -> client.delete().uri(path)
                "PUT" -> client.put().uri(path).bodyValue(body!!)
                else -> client.post().uri(path).bodyValue(body!!)
            }
        val result =
            spec
                .header(HttpHeaders.AUTHORIZATION, token)
                .headers { h -> cabeceras.forEach(h::set) }
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to (result.responseBody ?: "")
    }

    // un 200, para leer con jsonPath
    protected fun get(
        path: String,
        token: String = this.token
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk

    protected fun post(
        path: String,
        body: Map<String, Any?>,
        token: String = this.token
    ): JsonNode = tree(send("POST", path, body, HttpStatus.CREATED, token))

    // un 400 cuyo primer error nombra `field`: el problem, para leer sus mensajes
    protected fun rejected(
        method: String,
        path: String,
        body: Map<String, Any?>?,
        field: String,
        token: String = this.token,
        cabeceras: Map<String, String> = emptyMap()
    ): JsonNode {
        val problem = tree(send(method, path, body, HttpStatus.BAD_REQUEST, token, cabeceras))
        assertEquals(field, problem["errors"][0]["field"].asString(), problem.toString())
        return problem
    }

    protected fun tree(body: String): JsonNode = json.readValue(body, JsonNode::class.java)

    // un usuario (no ADMIN) con uno de los roles de roles.json
    protected fun funcionario(rol: String): String = usuario(rol).token

    // un usuario con uno de los roles de roles.json, con su correo: el cajero de la sesión es su correo
    protected fun cuenta(rol: String): Cuenta = usuario(rol)

    // un usuario (no ADMIN) con un rol propio: lo que puede hacer
    protected fun funcionario(permisos: List<Map<String, Any?>>): String {
        val rol = uniqueName("ROL").uppercase()
        send("POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED)
        send("PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
        return usuario(rol).token
    }

    private fun usuario(rol: String): Cuenta {
        val email = "${uniqueName(rol.lowercase())}@caja.test"
        send("POST", "/api/users", mapOf("email" to email, "displayName" to rol, "password" to CLAVE, "roles" to listOf(rol)), HttpStatus.CREATED)
        return Cuenta(bearer(email, CLAVE), email)
    }

    // el Bearer de un usuario y su correo
    protected data class Cuenta(
        val token: String,
        val email: String
    )

    // objectName null: todos los objetos de la organización
    protected fun permiso(
        objeto: String?,
        accion: String
    ) = mapOf("objectName" to objeto, "action" to accion)

    // registros

    // ocho dígitos, únicos para la base compartida: un documento, una referencia
    protected fun unico(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isDigit() }
            .padEnd(8, '0')
            .take(8)

    // un registro creado por la api de core, como admin: su id
    protected fun registro(
        objeto: String,
        atributos: Map<String, Any?>
    ): String = post("/api/objects/$objeto/records", mapOf("attributes" to atributos))["id"].asString()

    // una caja activa nueva, sin área. la serie, cinco caracteres hexadecimales si no se pide otra: única en la base
    // compartida
    protected fun nuevaCaja(
        serie: String = uniqueName("").uppercase().take(5),
        activa: Boolean = true
    ): CajaDePrueba {
        val codigo = "C-${unico()}"
        val id = registro("caja", mapOf("codigo" to codigo, "nombre" to "VENTANILLA $codigo", "serie" to serie, "activa" to activa))
        return CajaDePrueba(id, codigo, serie)
    }

    protected data class CajaDePrueba(
        val id: String,
        val codigo: String,
        val serie: String
    )

    // los registros de un objeto que cumplen los filtros, leídos como admin por la api de core
    protected fun registros(
        objeto: String,
        vararg filtros: Pair<String, String>
    ): List<JsonNode> {
        val query = (filtros.toList() + ("size" to "200")).joinToString("&") { (k, v) -> "$k=$v" }
        return tree(send("GET", "/api/objects/$objeto/records?$query", null, HttpStatus.OK))["content"].toList()
    }

    // el cuerpo de una orden de cobro válida de rentas, con una referencia nueva
    protected fun orden(vararg cambios: Pair<String, Any?>): Map<String, Any?> =
        mapOf(
            "sistema_origen" to "rentas",
            "referencia_externa" to "PREDIAL-2026-${unico()}",
            "concepto" to "IMPUESTO PREDIAL 2026 - CUOTA 1",
            "detalle" to "predio U-0001",
            "importe" to "150.50",
            "fecha_exigibilidad" to "2026-02-28",
            "actualizado_a" to "2026-03-15",
            "pagador_documento" to "4${unico().take(7)}",
            "pagador_nombre" to "FLORES OTINIANO JUNIOR",
            "pagador_externo_id" to 1234,
            "observacion" to "emisión de la cuota 1"
        ) + cambios

    private fun JsonNode.names(): Set<String> = iterator().asSequence().map { it["name"].asString() }.toSet()

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()

        // core pide al menos 8 caracteres
        const val CLAVE = "clave-del-funcionario"
    }
}
