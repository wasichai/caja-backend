package caja.emision

import caja.CajaApiTest
import caja.comun.LIMA
import caja.comun.Transaccion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.time.LocalDate
import java.util.UUID

// el original lee la historia del turno en UNA foto (Transaccion.lectura), como ConsultaDelTurno: la historia son dos
// consultas, una a cierre_turno y otra a reversion_cierre, y un cierre y su reversión que se confirman ENTRE las dos
// dejaban ver la reversión sin su cierre. CierreDeTurno rechaza esa historia rota, y el original contestaba un 500
// (hallazgo de la revisión del PR 7). la carrera se fuerza: otra transacción toma la tabla de reversion_cierre en
// exclusiva, el original lee los cierres y se queda esperando en las reversiones; entonces la otra escribe un cierre
// y su reversión en el mismo turno y confirma
class OriginalEnUnaFotoApiTest : CajaApiTest() {
    @Autowired
    lateinit var db: DatabaseClient

    @Autowired
    lateinit var schemas: WasichaiSchemas

    @Autowired
    lateinit var transaccion: Transaccion

    @Test
    fun `un cierre y su reversion confirmados entre las dos lecturas de la historia no rompen el original`() =
        runBlocking {
            // un turno cerrado y reversado como molde: sus filas se copian en el otro turno
            val supervisor = cuenta("SUPERVISOR_CAJA")
            val tasa = codigoDeTasa()
            nuevaTasa(tasa, "12.30", LocalDate.now(LIMA).minusDays(1))
            val molde = nuevaCaja()
            post(TASAS, cobroDeTasa(molde, tasa), supervisor.token)
            post(CIERRE, mapOf("caja" to molde.codigo, "observacion" to "cierre del turno molde"), supervisor.token)
            post(REVERSION, mapOf("caja" to molde.codigo, "motivo" to "MOLDE", "observacion" to "reversión del turno molde"), supervisor.token)
            val turnoMolde = registros("turno", "caja" to molde.id).single()["id"].asString()
            val cierreMolde = registros("cierre_turno", "turno" to turnoMolde).single()["id"].asString()
            val reversionMolde = registros("reversion_cierre", "turno" to turnoMolde).single()["id"].asString()

            // el turno abierto de un cajero, con el recibo cuyo original se pide
            val cajero = cuenta("CAJERO")
            val caja = nuevaCaja()
            val numero = post(TASAS, cobroDeTasa(caja, tasa), cajero.token)["recibo"]["numero_impreso"].asString()
            val turno = registros("turno", "caja" to caja.id).single()["id"].asString()

            val cierres = tabla("cierre_turno")
            val reversiones = tabla("reversion_cierre")
            val tomada = CompletableDeferred<Unit>()
            val esperando = CompletableDeferred<Unit>()
            val otra =
                async(Dispatchers.IO) {
                    transaccion.en {
                        sql("LOCK TABLE ${reversiones.nombre} IN ACCESS EXCLUSIVE MODE")
                        tomada.complete(Unit)
                        esperando.await()
                        val cierre = copiar(cierres, cierreMolde, mapOf("turno" to turno))
                        copiar(reversiones, reversionMolde, mapOf("turno" to turno, "cierre_revertido" to cierre.toString()))
                    }
                }
            tomada.await()
            val original = async(Dispatchers.IO) { exchange("GET", "/api/caja/recibos/$numero/pdf", null, cajero.token) }
            // el original ya leyó los cierres (ninguno) y espera la tabla de las reversiones
            withTimeout(20_000) {
                while (esperandoEn(reversiones) == 0L) delay(20)
            }
            esperando.complete(Unit)
            otra.await()

            val (estado, cuerpo) = original.await()
            assertEquals(HttpStatus.OK, estado, cuerpo.take(500))
        }

    // la tabla física de un objeto y sus columnas por nombre de campo
    private class Tabla(
        val nombre: String,
        val columnas: Map<String, String>
    )

    private suspend fun tabla(objeto: String): Tabla {
        val filas =
            db
                .sql(
                    "SELECT o.physical_table AS tabla, f.name AS campo, f.column_name AS columna FROM ${schemas.metadata}.custom_objects o " +
                        "JOIN ${schemas.metadata}.custom_fields f ON f.object_id = o.id WHERE o.name = :objeto"
                ).bind("objeto", objeto)
                .map { row, _ -> listOf("tabla", "campo", "columna").map { row.get(it, String::class.java)!! } }
                .all()
                .collectList()
                .awaitSingle()
        return Tabla(schemas.dataTable(filas.first()[0]), filas.associate { it[1] to it[2] })
    }

    // copia una fila con algunos campos cambiados: su id nuevo
    private suspend fun copiar(
        tabla: Tabla,
        id: String,
        cambios: Map<String, String>
    ): UUID {
        val porColumna = cambios.mapKeys { tabla.columnas.getValue(it.key) }
        val columnas =
            db
                .sql("SELECT column_name FROM information_schema.columns WHERE table_schema = :esquema AND table_name = :tabla AND column_name <> 'id'")
                .bind("esquema", schemas.data)
                .bind("tabla", tabla.nombre.substringAfter('.').trim('"'))
                .map { row, _ -> row.get(0, String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        val destino = columnas.joinToString(", ") { SqlIdentifier.quote(it) }
        val origen =
            columnas.joinToString(", ") { c ->
                if (c in porColumna) {
                    "${SqlIdentifier.literal(porColumna.getValue(c))}::uuid"
                } else {
                    SqlIdentifier.quote(c)
                }
            }
        return db
            .sql("INSERT INTO ${tabla.nombre} ($destino) SELECT $origen FROM ${tabla.nombre} WHERE id = :id RETURNING id")
            .bind("id", UUID.fromString(id))
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitSingle()
    }

    private suspend fun esperandoEn(tabla: Tabla): Long =
        db
            .sql("SELECT count(*) AS n FROM pg_locks WHERE relation = to_regclass(:tabla) AND NOT granted")
            .bind("tabla", tabla.nombre)
            .map { row, _ -> (row.get("n") as Number).toLong() }
            .one()
            .awaitSingle()

    private suspend fun sql(sentencia: String) {
        db
            .sql(sentencia)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun cobroDeTasa(
        caja: CajaDePrueba,
        codigo: String
    ) = mapOf(
        "caja" to caja.codigo,
        "forma_pago" to "EFECTIVO",
        "conceptos" to listOf(mapOf("codigo" to codigo, "cantidad" to 1)),
        "observacion" to "cobro de tasas en ventanilla"
    )

    private companion object {
        const val TASAS = "/api/caja/cobros/tasas"
        const val CIERRE = "/api/caja/turnos/cierre"
        const val REVERSION = "/api/caja/turnos/reversion"
    }
}
