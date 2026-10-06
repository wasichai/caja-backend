package caja.recibo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// el recibo no se corrige (V29 de caja y TABLAS_INMUTABLES de su escáner de fuentes): src/main no tiene ningún
// replace, update ni delete sobre el recibo, sus líneas, su anulación, sus reimpresiones ni su evento. anular es
// agregar una fila, y la prueba lo vigila en el código además de en roles.json (test_apply_roles.py). lo mismo vale
// para el cierre del turno, sus líneas y su reversión (regla 4, V32 de caja): un cierre se reversa con otra fila. las
// excepciones son dos, las dos sobre el evento del pago: la explicación de un pago MUERTO, que lo pasa a EXPLICADO, y la
// marca del publicador del buzón, que anota cada intento de entrega
class InmutabilidadDelReciboTest {
    private val inmutables =
        listOf("RECIBO", "LINEA_RECIBO", "ANULACION_RECIBO", "REIMPRESION_RECIBO", "PAGO_EVENTO", "CIERRE_TURNO", "CIERRE_TURNO_LINEA", "REVERSION_CIERRE")
    private val nombres =
        listOf(
            "recibo",
            "linea_recibo",
            "anulacion_recibo",
            "reimpresion_recibo",
            "pago_evento",
            "cierre_turno",
            "cierre_turno_linea",
            "reversion_cierre"
        )

    // una escritura que cambia o borra (Registros.replace, RecordService.update o delete, o una ayuda que las envuelva)
    // cuyo primer argumento nombra un objeto inmutable, por su constante o por su nombre
    private val cambio =
        Regex(
            "\\b(replace|update|delete|cambiar|borrar)\\(\\s*(" +
                inmutables.joinToString("|") +
                "|\"(" +
                nombres.joinToString("|") +
                ")\")\\s*[,)]"
        )

    @Test
    fun `ningun replace, update ni delete sobre el recibo, sus lineas, su anulacion, sus reimpresiones, su evento ni el cierre, salvo explicar y marcar`() {
        val fuentes = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(fuentes.size > 10, "no se encontraron las fuentes: ${fuentes.size}")

        val hallazgos =
            fuentes.flatMap { fuente ->
                // sobre el texto entero: una llamada con la razón al final se parte en varias líneas (ktlint)
                val texto = fuente.readText()
                cambio
                    .findAll(texto)
                    .map { hallazgo ->
                        val linea = texto.substring(0, hallazgo.range.first).count { it == '\n' } + 1
                        "${fuente.path}:$linea: ${hallazgo.value.replace(Regex("\\s+"), "")}"
                    }.toList()
            }

        // las dos únicas ediciones del buzón, cada una bajo el candado de su evento: un pago MUERTO pasa a EXPLICADO, por
        // escrito y como el supervisor (roles.json da UPDATE sobre pago_evento solo a SUPERVISOR_CAJA), y el publicador
        // anota la entrega de uno PENDIENTE como la plataforma, con RecordService (BuzonStore.marcar)
        val (explicacion, otros) = hallazgos.partition { it.contains("caja/buzon/ExplicarPagoSinEntregar.kt") && it.contains("replace(PAGO_EVENTO,") }
        val (marca, resto) = otros.partition { it.contains("caja/buzon/BuzonStore.kt") && it.contains("update(PAGO_EVENTO,") }
        assertEquals(emptyList<String>(), resto)
        assertEquals(1, explicacion.size, "la explicación de un pago sin entregar, y solo ella: $explicacion")
        assertEquals(1, marca.size, "la marca del publicador del buzón, y solo ella: $marca")
    }

    // caja no borra nada: ni una puerta para borrar en src/main (un records.delete, un Registros.delete o una ayuda que
    // los envuelva, como la Listas de srtm), aunque hoy nadie la llame. una puerta latente es la que alguien usa mañana.
    // GuardiaDeEscrituras rechaza el borrado de todo objeto de caja, pero no lo expresa con un delete: no queda ninguno
    @Test
    fun `src main no tiene ninguna puerta para borrar`() {
        val borrar = Regex("\\.delete\\(|fun (<[^>]*> )?(delete|borrar|cambiar)\\(")
        val hallazgos =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { fuente ->
                    fuente.readLines().mapIndexedNotNull { i, linea ->
                        if (borrar.containsMatchIn(linea)) "${fuente.path}:${i + 1}: ${linea.trim()}" else null
                    }
                }.toList()
        assertEquals(emptyList<String>(), hallazgos)
    }

    @Test
    fun `el vigia reconoce un cambio y deja pasar el de una orden`() {
        assertTrue(cambio.containsMatchIn("registros.replace(RECIBO, Recibo::class.java, id, mapOf())"))
        assertTrue(cambio.containsMatchIn("records.update(\"pago_evento\", id, request)"))
        assertTrue(cambio.containsMatchIn("registros.delete(ANULACION_RECIBO, id)"))
        assertTrue(cambio.containsMatchIn("registros.replace(CIERRE_TURNO, CierreTurno::class.java, id, mapOf())"))
        assertTrue(cambio.containsMatchIn("records.delete(\"reversion_cierre\", id)"))
        assertTrue(!cambio.containsMatchIn("registros.create(CIERRE_TURNO_LINEA, CierreTurnoLinea::class.java, atributos)"))
        assertTrue(!cambio.containsMatchIn("registros.replace(ORDEN_DE_COBRO, OrdenDeCobro::class.java, id, cambios)"))
        assertTrue(!cambio.containsMatchIn("registros.create(RECIBO, Recibo::class.java, atributos)"))
    }
}
