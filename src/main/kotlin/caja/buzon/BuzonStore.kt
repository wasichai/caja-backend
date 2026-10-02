package caja.buzon

import caja.cobro.Recibo
import caja.comun.ANULACION_RECIBO
import caja.comun.LINEA_RECIBO
import caja.comun.ORDEN_DE_COBRO
import caja.comun.PAGO_EVENTO
import caja.comun.RECIBO
import caja.comun.Transaccion
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// lo que el publicador lee y marca en la base, y NADA MÁS: es la ÚNICA clase de caja que toca tablas físicas con
// DatabaseClient (aparte de Candados y CerrojoBuzon, que solo toman candados). el trabajo de fondo no tiene un usuario
// para RecordService (CurrentUser exige uno, wasichai#18), así que se sigue el precedente de srtm
// (EmisionMasivaService): la tabla física de cada objeto, por organización, se resuelve en custom_objects y
// custom_fields de wasichai, y cada marca se audita con AuditService y usuario null (la escribió el sistema). lo que
// se escribe aquí no pasa por los RecordChangeListener
//
// CADA MARCA VA EN SU PROPIA TRANSACCIÓN Y ES CONDICIONAL: WHERE estado = 'PENDIENTE' AND intentos = :leidos. dos
// publicadores que leyeron el mismo evento cuentan un solo intento, y una marca nunca pisa un evento que ya cambió (uno
// EXPLICADO, uno que otro entregó). la llamada al destino ocurre entre una lectura y una marca, con ninguna
// transacción abierta (PublicadorDelBuzon)
@Component
class BuzonStore(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val auditoria: AuditService,
    private val transaccion: Transaccion
) {
    // el buzón de cada organización que tiene el modelo de caja: las tablas físicas del evento y de lo que se comprueba
    // antes de enviarlo
    suspend fun buzones(): List<Buzon> {
        val filas =
            db
                .sql(
                    """
                    SELECT o.organization_id AS organizacion, o.name AS objeto, o.physical_table AS tabla, f.name AS campo, f.column_name AS columna
                    FROM ${schemas.metadata}.custom_objects o
                    JOIN ${schemas.metadata}.custom_fields f ON f.object_id = o.id
                    WHERE o.name IN (:objetos)
                    """.trimIndent()
                ).bind("objetos", CAMPOS.keys.toList())
                .map { row, _ ->
                    Columna(
                        row.get("organizacion", UUID::class.java)!!,
                        row.get("objeto", String::class.java)!!,
                        row.get("tabla", String::class.java)!!,
                        row.get("campo", String::class.java)!!,
                        row.get("columna", String::class.java)!!
                    )
                }.all()
                .asFlow()
                .toList()
        return filas.groupBy { it.organizacion }.mapNotNull { (organizacion, columnas) ->
            val tablas =
                CAMPOS.mapValues { (objeto, campos) ->
                    val delObjeto = columnas.filter { it.objeto == objeto }
                    val porCampo = delObjeto.associate { it.campo to it.columna }
                    if (delObjeto.isEmpty() || !porCampo.keys.containsAll(campos)) return@mapNotNull null
                    Tabla(schemas.dataTable(delObjeto.first().tabla), porCampo)
                }
            Buzon(
                organizacion,
                tablas.getValue(PAGO_EVENTO),
                tablas.getValue(RECIBO),
                tablas.getValue(LINEA_RECIBO),
                tablas.getValue(ANULACION_RECIBO),
                tablas.getValue(ORDEN_DE_COBRO)
            )
        }
    }

    // hasta cuantos eventos PENDIENTE, por orden de creación (el del cobro)
    suspend fun pendientes(
        buzon: Buzon,
        cuantos: Int
    ): List<EventoDelBuzon> {
        val t = buzon.eventos
        return db
            .sql(
                "SELECT id, created_at, ${t.c("evento_id")} AS evento_id, ${t.c("tipo")} AS tipo, ${t.c("sistema_destino")} AS destino, " +
                    "${t.c("recibo")} AS recibo, ${t.c("turno")} AS turno, ${t.c("cuerpo")} AS cuerpo, ${t.c("intentos")} AS intentos " +
                    "FROM ${t.nombre} WHERE organization_id = :organizacion AND ${t.c("estado")} = :pendiente ORDER BY created_at, id LIMIT :cuantos"
            ).bind("organizacion", buzon.organizacion)
            .bind("pendiente", PENDIENTE)
            .bind("cuantos", cuantos)
            .map { row, _ ->
                EventoDelBuzon(
                    id = row.get("id", UUID::class.java)!!,
                    creadoEn = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
                    eventoId = row.get("evento_id", UUID::class.java)!!.toString(),
                    tipo = row.get("tipo", String::class.java),
                    sistemaDestino = row.get("destino", String::class.java)!!,
                    recibo = row.get("recibo", UUID::class.java)?.toString(),
                    turno = row.get("turno", UUID::class.java)?.toString(),
                    cuerpo = row.get("cuerpo", String::class.java),
                    intentos = (row.get("intentos") as Number?)?.toLong() ?: 0L
                )
            }.all()
            .asFlow()
            .toList()
    }

    // el recibo del evento, tal como está, con lo que hace falta para volver a componer el cuerpo de su evento
    // (incoherencia): el recibo, sus líneas, el actualizado_a de cada orden, su anulación y los eventos de su buzón por
    // (created_at, id), cada fila con su sello (su created_at, o el updated_at de la orden). null si no existe
    suspend fun recibo(
        buzon: Buzon,
        reciboId: String?
    ): ReciboDelEvento? {
        val id = reciboId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val r = buzon.recibos
        val (creadoEn, recibo) =
            filas(
                "SELECT ${r.c("serie")} AS serie, ${r.c("numero_impreso")} AS numero_impreso, ${r.c("cajero")} AS cajero, " +
                    "${r.c("pagador_documento")} AS pagador_documento, ${r.c("pagador_nombre")} AS pagador_nombre, " +
                    "${r.c("pagador_externo_id")} AS pagador_externo_id, ${r.c("forma_pago")} AS forma_pago, ${r.c("tipo_pago")} AS tipo_pago, " +
                    "${r.c("total")} AS total, ${r.c("actualizado_a")} AS actualizado_a, created_at " +
                    "FROM ${r.nombre} WHERE id = :id AND organization_id = :organizacion",
                buzon,
                id
            ) { row ->
                sello(row) to
                    Recibo(
                        id = id.toString(),
                        serie = row.get("serie", String::class.java),
                        numeroImpreso = row.get("numero_impreso", String::class.java),
                        cajero = row.get("cajero", String::class.java),
                        pagadorDocumento = row.get("pagador_documento", String::class.java),
                        pagadorNombre = row.get("pagador_nombre", String::class.java),
                        pagadorExternoId = (row.get("pagador_externo_id") as Number?)?.toLong(),
                        formaPago = row.get("forma_pago", String::class.java),
                        tipoPago = row.get("tipo_pago", String::class.java),
                        total = row.get("total", BigDecimal::class.java),
                        actualizadoA = row.get("actualizado_a", LocalDate::class.java)
                    )
            }.singleOrNull() ?: return null
        val l = buzon.lineas
        val lineas =
            filas(
                "SELECT ${l.c("orden")} AS orden, ${l.c("sistema_origen")} AS sistema, ${l.c("referencia_externa")} AS referencia, " +
                    "${l.c("monto")} AS monto, created_at FROM ${l.nombre} WHERE ${l.c("recibo")} = :id AND organization_id = :organizacion",
                buzon,
                id
            ) { row ->
                LineaDelEvento(
                    orden = row.get("orden", UUID::class.java)?.toString(),
                    sistemaOrigen = row.get("sistema", String::class.java),
                    referenciaExterna = row.get("referencia", String::class.java),
                    monto = row.get("monto", BigDecimal::class.java),
                    creadoEn = sello(row)
                )
            }
        val o = buzon.ordenes
        val ids = lineas.mapNotNull { it.orden }.distinct().map(UUID::fromString)
        val ordenes =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                db
                    .sql(
                        "SELECT id, ${o.c("actualizado_a")} AS actualizado_a, updated_at FROM ${o.nombre} " +
                            "WHERE id = ANY(:ids) AND organization_id = :organizacion"
                    ).bind("ids", ids.toTypedArray())
                    .bind("organizacion", buzon.organizacion)
                    .map { row, _ ->
                        row.get("id", UUID::class.java)!!.toString() to
                            OrdenDelEvento(row.get("actualizado_a", LocalDate::class.java), sello(row, "updated_at"))
                    }.all()
                    .asFlow()
                    .toList()
                    .toMap()
            }
        val a = buzon.anulaciones
        val anulacion =
            filas(
                "SELECT ${a.c("motivo")} AS motivo, ${a.c("fecha")} AS fecha, created_at FROM ${a.nombre} " +
                    "WHERE ${a.c("recibo")} = :id AND organization_id = :organizacion ORDER BY created_at, id",
                buzon,
                id
            ) { row -> AnulacionDelEvento(row.get("motivo", String::class.java), row.get("fecha", LocalDate::class.java), sello(row)) }
                .firstOrNull()
        val e = buzon.eventos
        val eventos =
            filas(
                "SELECT id, ${e.c("evento_id")} AS evento_id, ${e.c("tipo")} AS tipo, created_at FROM ${e.nombre} " +
                    "WHERE ${e.c("recibo")} = :id AND organization_id = :organizacion ORDER BY created_at, id",
                buzon,
                id
            ) { row ->
                EventoDelRecibo(
                    row.get("id", UUID::class.java)!!,
                    row.get("evento_id", UUID::class.java)!!.toString(),
                    row.get("tipo", String::class.java),
                    sello(row)
                )
            }
        return ReciboDelEvento(recibo, creadoEn, lineas, ordenes, anulacion, eventos)
    }

    // el sello de una fila: el instante que postgres le dio con now(), el comienzo de su transacción (incoherencia)
    private fun sello(
        row: Readable,
        columna: String = "created_at"
    ): Instant = row.get(columna, OffsetDateTime::class.java)!!.toInstant()

    // las filas de una consulta por el id de un recibo, en la organización del buzón
    private suspend fun <T : Any> filas(
        sql: String,
        buzon: Buzon,
        id: UUID,
        leer: (Readable) -> T
    ): List<T> =
        db
            .sql(sql)
            .bind("id", id)
            .bind("organizacion", buzon.organizacion)
            .map { row, _ -> leer(row) }
            .all()
            .asFlow()
            .toList()

    // el evento llegó: ENTREGADO, con su hora y su intento. false si ya no estaba como se leyó (otro lo marcó)
    suspend fun entregado(
        buzon: Buzon,
        evento: EventoDelBuzon,
        cuando: OffsetDateTime
    ): Boolean {
        val t = buzon.eventos
        return marcar(
            buzon,
            evento,
            "${t.c("estado")} = :estado, ${t.c("entregado_en")} = :cuando, ${t.c("intentos")} = ${t.c("intentos")} + 1, ${t.c("ultimo_error")} = NULL",
            mapOf("estado" to ENTREGADO, "cuando" to cuando),
            mapOf("estado" to ENTREGADO, "intentos" to evento.intentos + 1, "entregado_en" to cuando.toString(), "ultimo_error" to null)
        )
    }

    // el evento no llegó: cuenta su intento con su motivo, y queda PENDIENTE o MUERTO. false si ya no estaba como se leyó
    suspend fun fallido(
        buzon: Buzon,
        evento: EventoDelBuzon,
        error: String,
        muere: Boolean
    ): Boolean {
        val t = buzon.eventos
        val estado = if (muere) MUERTO else PENDIENTE
        return marcar(
            buzon,
            evento,
            "${t.c("estado")} = :estado, ${t.c("intentos")} = ${t.c("intentos")} + 1, ${t.c("ultimo_error")} = :error",
            mapOf("estado" to estado, "error" to error),
            mapOf("estado" to estado, "intentos" to evento.intentos + 1, "ultimo_error" to error)
        )
    }

    // una marca, en su propia transacción: el UPDATE condicional y, si cambió la fila, su auditoría
    private suspend fun marcar(
        buzon: Buzon,
        evento: EventoDelBuzon,
        cambios: String,
        valores: Map<String, Any>,
        despues: Map<String, Any?>
    ): Boolean {
        val t = buzon.eventos
        return transaccion.en {
            var sentencia =
                db
                    .sql(
                        "UPDATE ${t.nombre} SET $cambios, updated_at = now() WHERE id = :id AND organization_id = :organizacion " +
                            "AND ${t.c("estado")} = :pendiente AND ${t.c("intentos")} = :leidos"
                    ).bind("id", evento.id)
                    .bind("organizacion", buzon.organizacion)
                    .bind("pendiente", PENDIENTE)
                    .bind("leidos", evento.intentos)
            valores.forEach { (nombre, valor) -> sentencia = sentencia.bind(nombre, valor) }
            val cambiadas = sentencia.fetch().awaitRowsUpdated()
            if (cambiadas > 0) {
                auditoria.record(
                    buzon.organizacion,
                    null,
                    PAGO_EVENTO,
                    evento.id,
                    AuditOperation.UPDATE,
                    before = mapOf("estado" to PENDIENTE, "intentos" to evento.intentos),
                    after = despues
                )
            }
            cambiadas > 0
        }
    }

    // la tabla física de un objeto en una organización, con sus columnas por nombre de campo
    class Tabla(
        val nombre: String,
        private val columnas: Map<String, String>
    ) {
        fun c(campo: String): String = SqlIdentifier.quote(columnas.getValue(campo))
    }

    class Buzon(
        val organizacion: UUID,
        val eventos: Tabla,
        val recibos: Tabla,
        val lineas: Tabla,
        val anulaciones: Tabla,
        val ordenes: Tabla
    )

    private class Columna(
        val organizacion: UUID,
        val objeto: String,
        val tabla: String,
        val campo: String,
        val columna: String
    )

    companion object {
        const val PENDIENTE = "PENDIENTE"
        const val ENTREGADO = "ENTREGADO"
        const val MUERTO = "MUERTO"

        // los campos que el publicador lee o escribe, por objeto: una organización sin alguno no tiene buzón
        private val CAMPOS =
            mapOf(
                PAGO_EVENTO to setOf("evento_id", "tipo", "sistema_destino", "recibo", "turno", "cuerpo", "estado", "intentos", "ultimo_error", "entregado_en"),
                RECIBO to
                    setOf(
                        "serie",
                        "numero_impreso",
                        "cajero",
                        "pagador_documento",
                        "pagador_nombre",
                        "pagador_externo_id",
                        "forma_pago",
                        "tipo_pago",
                        "total",
                        "actualizado_a"
                    ),
                LINEA_RECIBO to setOf("recibo", "orden", "sistema_origen", "referencia_externa", "monto"),
                ANULACION_RECIBO to setOf("recibo", "motivo", "fecha"),
                ORDEN_DE_COBRO to setOf("actualizado_a")
            )
    }
}

// un evento PENDIENTE como lo lee el publicador. intentos es el valor leído: la marca solo cuenta si sigue valiendo eso
data class EventoDelBuzon(
    val id: UUID,
    val creadoEn: Instant,
    val eventoId: String,
    val tipo: String?,
    val sistemaDestino: String,
    val recibo: String?,
    val turno: String?,
    val cuerpo: String?,
    val intentos: Long
)
