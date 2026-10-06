package caja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.PhysicalTableRecordStore
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

// la base de las pruebas de integración de la api de caja (portada de SrtmApiTest): antes de cada prueba, el modelo
// real (model/model.json, aplicado como lo hace model/apply.py) y los roles de model/roles.json (como
// model/apply_roles.py), el token del admin sembrado y las llamadas que hacen las pruebas. la base la comparten todas
// las clases de la corrida: cada registro lleva claves únicas
@TestPropertySource(properties = ["caja.municipalidad.nombre=${CajaApiTest.MUNICIPALIDAD}"])
abstract class CajaApiTest : WasichaiIntegrationTest() {
    // el del admin sembrado; una llamada toma otro donde la prueba lo necesita
    protected lateinit var token: String

    protected val json: JsonMapper get() = JSON

    // por debajo de las guardas (que viven en RecordService, no en el almacén): el almacén de wasichai, y la metadata que da su definición
    @Autowired
    private lateinit var conexion: DatabaseClient

    @Autowired
    private lateinit var esquemas: WasichaiSchemas

    @Autowired
    private lateinit var tipos: FieldTypeRegistry

    @Autowired
    private lateinit var metadatos: MetadataService

    @BeforeEach
    fun adminModeloYRoles() {
        token = bearer()
        applyModel()
        applyRoles()
    }

    protected fun modelo(): JsonNode = json.readTree(File("model/model.json"))

    // model/apply.py en kotlin: se crea lo que falta (objetos, campos, opciones de enum, relaciones obligatorias), un
    // campo que model.json ya no exige se relaja, lo demás se deja; las acciones declaradas de cada objeto. la fase 3, ya con las relaciones: las
    // uniqueConstraints de cada objeto y el unique de las relaciones que lo piden, solo si difieren
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

        fun uniques(obj: JsonNode): List<List<String>> = conjuntos(obj["uniqueConstraints"])

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
                        "apiOnly" to (obj["apiOnly"]?.asBoolean() ?: false),
                        "appendOnly" to (obj["appendOnly"]?.asBoolean() ?: false),
                        "fields" to fields.map(::payload),
                        // en el POST solo las que nombran campos propios: las de una relación esperan a la fase 3
                        "uniqueConstraints" to uniques(obj).filter { set -> set.all { n -> fields.any { it["name"].asString() == n } } }
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
        // las acciones declaradas de cada objeto: un 409 es que ya estaba (la base se vacía al empezar, pero se repite en una corrida)
        for (obj in model["objects"]) {
            for (accion in obj["actions"]?.toList() ?: emptyList()) {
                val cuerpo = mapOf("name" to accion["name"].asString(), "label" to accion["label"].asString())
                val (estado, respuesta) = exchange("POST", "/api/metadata/objects/${obj["name"].asString()}/actions", cuerpo)
                assertTrue(estado == HttpStatus.CREATED || estado == HttpStatus.CONFLICT, "acción ${accion["name"]}: $estado $respuesta")
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

        // fase 3: el objeto entero con la lista completa de uniqueConstraints (el PUT lo reemplaza, y wasichai omite la
        // clave si está vacía) y el unique de la relación
        val guardados = tree(send("GET", "/api/objects", null, HttpStatus.OK)).associateBy { it["name"].asString() }
        for (obj in model["objects"]) {
            val name = obj["name"].asString()
            val queremos = uniques(obj)
            val tiene = conjuntos(guardados.getValue(name)["uniqueConstraints"])
            if (tiene == queremos) continue
            send(
                "PUT",
                "/api/objects/$name",
                mapOf(
                    "label" to obj["label"].asString(),
                    "pluralLabel" to obj["pluralLabel"].asString(),
                    "description" to obj["description"]?.asString(),
                    "enabled" to true,
                    "apiOnly" to (obj["apiOnly"]?.asBoolean() ?: false),
                    "appendOnly" to (obj["appendOnly"]?.asBoolean() ?: false),
                    "uniqueConstraints" to queremos
                ),
                HttpStatus.OK
            )
        }
        for (rel in model["relationships"]) {
            if (rel["unique"]?.asBoolean() != true) continue
            val source = rel["source"].asString()
            val campo = rel["fieldName"].asString()
            val guardado = tree(send("GET", "/api/metadata/objects/$source/fields", null, HttpStatus.OK)).first { it["name"].asString() == campo }
            if (guardado["unique"]?.asBoolean() != true) {
                send("PUT", "/api/metadata/objects/$source/fields/$campo", mapOf("unique" to true), HttpStatus.OK)
            }
        }
    }

    // las listas de campos de un uniqueConstraints (wasichai omite la clave si está vacía)
    private fun conjuntos(nodo: JsonNode?): List<List<String>> = nodo?.toList()?.map { set -> set.toList().map { it.asString() } } ?: emptyList()

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
    protected fun funcionario(permisos: List<Map<String, Any?>>): String = usuario(rolPropio(permisos)).token

    // un rol propio con esos permisos, para darlo a varios usuarios con cuenta(rol): su nombre
    protected fun rolPropio(permisos: List<Map<String, Any?>>): String {
        val rol = uniqueName("ROL").uppercase()
        send("POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED)
        send("PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
        return rol
    }

    // la cuenta de servicio de un sistema de origen: su nombre (el sistema_origen de sus órdenes, de 20 caracteres a lo sumo)
    // y su Bearer, que dura 15 minutos
    protected fun cuentaDeServicio(
        prefijo: String = "rentas",
        rol: String = "SISTEMA_ORIGEN"
    ): Pair<String, String> {
        val nombre = uniqueName(prefijo)
        val creada = tree(send("POST", "/api/service-accounts", mapOf("name" to nombre, "roles" to listOf(rol)), HttpStatus.CREATED))
        val acceso =
            tree(
                send(
                    "POST",
                    "/api/auth/token",
                    mapOf("clientId" to creada["clientId"].asString(), "clientSecret" to creada["clientSecret"].asString()),
                    HttpStatus.OK
                )
            )
        return nombre to "Bearer ${acceso["token"].asString()}"
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

    // una tasa nueva del TUPA en una vigencia, con un área nueva: su id. el importe es de la prueba, no de una tarifa
    // real
    protected fun nuevaTasa(
        codigo: String,
        importe: String,
        desde: LocalDate,
        hasta: LocalDate? = null,
        descripcion: String = "CONSTANCIA DE LA PRUEBA $codigo"
    ): String {
        val area = registro("area", mapOf("codigo" to "A-${unico()}", "nombre" to "ÁREA DE LA PRUEBA", "activa" to true))
        return registro(
            "tasa",
            mapOf(
                "codigo" to codigo,
                "descripcion" to descripcion,
                "partida_presupuestal" to "1.3.1.1.1.1",
                "importe" to importe,
                "vigencia_desde" to desde.toString(),
                "vigencia_hasta" to hasta?.toString(),
                "documento_fuente" to "ORDENANZA DE LA PRUEBA",
                "area" to area
            )
        )
    }

    // un código de tasa nuevo, único en la base compartida
    protected fun codigoDeTasa(): String = "T-${unico()}"

    // un recibo escrito en la base, con su turno, sin pasar por la cobranza (forjarEnLaBase): para fijar un instante de
    // emisión o un recibo sin evento. el número va en la serie de la caja; su id
    protected fun reciboEscrito(
        caja: CajaDePrueba,
        numero: Long,
        emitidoEn: OffsetDateTime,
        documento: String,
        cajero: String = "escrito@caja.test",
        tipoPago: String = "NORMAL",
        total: String = "10.00"
    ): String {
        val fecha = emitidoEn.atZoneSameInstant(ZoneId.of("America/Lima")).toLocalDate()
        // un turno por (caja, cajero, fecha): si ya existe se usa, y si no se forja
        val turno =
            registros("turno", "caja" to caja.id, "cajero" to cajero, "fecha" to fecha.toString()).firstOrNull()?.get("id")?.asString()
                ?: forjarEnLaBase(
                    "turno",
                    mapOf(
                        "caja" to caja.id,
                        "cajero" to cajero,
                        "fecha" to fecha.toString(),
                        "abierto_en" to emitidoEn.toString(),
                        "observacion" to "turno escrito por la prueba"
                    )
                )
        return forjarEnLaBase(
            "recibo",
            mapOf(
                "serie" to caja.serie,
                "numero" to numero,
                "numero_impreso" to "${caja.serie}-${"%07d".format(numero)}",
                "caja" to caja.id,
                "turno" to turno,
                "cajero" to cajero,
                "pagador_documento" to documento,
                "pagador_nombre" to "PAGADOR DE LA PRUEBA",
                "emitido_en" to emitidoEn.toString(),
                "forma_pago" to "EFECTIVO",
                "tipo_pago" to tipoPago,
                "total" to total,
                "actualizado_a" to fecha.toString(),
                "observacion" to "recibo escrito por la prueba"
            )
        )
    }

    // los registros de un objeto que cumplen los filtros, leídos como admin por la api de core
    protected fun registros(
        objeto: String,
        vararg filtros: Pair<String, String>
    ): List<JsonNode> {
        val query = (filtros.toList() + ("size" to "200")).joinToString("&") { (k, v) -> "$k=$v" }
        return tree(send("GET", "/api/objects/$objeto/records?$query", null, HttpStatus.OK))["content"].toList()
    }

    // un registro escrito EN LA BASE, por debajo de GuardiaDeEscrituras: la puerta que queda abierta (quien escribe en la
    // base directamente), y la única por la que una prueba escribe un objeto de caja sin pasar por caja: un recibo roto,
    // una línea o un evento forjados, un recibo con un instante fijo. el almacén de wasichai sin la guarda, en la
    // organización del admin y a su nombre; fuera de una transacción, así que lleva su propio sello. su id
    protected fun forjarEnLaBase(
        objeto: String,
        atributos: Map<String, Any?>
    ): String =
        runBlocking {
            val (organizacion, usuario) = admin()
            almacen().insert(metadatos.loadDefinition(organizacion, objeto), organizacion, usuario, atributos, emptyMap()).id.toString()
        }

    // cambia campos de un registro EN LA BASE, por debajo de GuardiaDeEscrituras: lo que haría el publicador del buzón al
    // entregar un pago con el buzón apagado, o quien toca la base por fuera. como el PUT de core, reemplaza cada campo:
    // va lo guardado con los cambios encima
    protected fun cambiarEnLaBase(
        objeto: String,
        id: String,
        vararg cambios: Pair<String, Any?>
    ) {
        val guardado = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))["attributes"]

        @Suppress("UNCHECKED_CAST")
        val atributos = json.convertValue(guardado, Map::class.java) as Map<String, Any?> + cambios
        runBlocking {
            val (organizacion, usuario) = admin()
            almacen().update(metadatos.loadDefinition(organizacion, objeto), organizacion, usuario, UUID.fromString(id), atributos, emptyMap())
        }
    }

    private fun almacen() = PhysicalTableRecordStore(conexion, esquemas, tipos)

    // la organización del admin sembrado: la de todo lo que escribe una prueba
    protected fun organizacion(): UUID = admin().first

    // la organización y el id del admin sembrado
    private fun admin(): Pair<UUID, UUID> {
        val yo = tree(send("GET", "/api/auth/me", null, HttpStatus.OK))
        return UUID.fromString(yo["organizationId"].asString()) to UUID.fromString(yo["userId"].asString())
    }

    // cambia campos de un registro como admin por la api de core, sin pasar por caja: el admin al dar de baja una caja.
    // solo vale para lo que no es de caja (una caja, un área): un objeto de caja no se escribe por esa puerta
    // (GuardiaDeEscrituras), y se cambia con cambiarEnLaBase. core reemplaza todo: se manda lo guardado con los cambios
    // encima
    protected fun cambiarComoAdmin(
        objeto: String,
        id: String,
        vararg cambios: Pair<String, Any?>
    ) {
        val guardado = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))["attributes"]
        val atributos = json.convertValue(guardado, Map::class.java) + cambios
        send("PUT", "/api/objects/$objeto/records/$id", mapOf("attributes" to atributos), HttpStatus.OK)
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

    companion object {
        const val MUNICIPALIDAD = "MUNICIPALIDAD DISTRITAL DE PRUEBA"

        private val JSON: JsonMapper = JsonMapper.builder().build()

        // core pide al menos 8 caracteres
        private const val CLAVE = "clave-del-funcionario"
    }
}
