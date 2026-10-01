package caja.cobro

import caja.CajaApiTest
import caja.comun.LIMA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

// la vista previa del total: ningún total sale del cliente. aplica las mismas reglas que el cobro, sin candados ni
// escritura, y un problema va en motivos, no es un error. el total de la vista previa es el del recibo emitido después
class VistaPreviaApiTest : CajaApiTest() {
    private val hoy: LocalDate get() = LocalDate.now(LIMA)

    @Test
    fun `la vista previa de unas ordenes da el total del recibo que se emite despues, sin escribir nada`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val una = post(ORDENES, orden("importe" to "150.50"))["orden_id"].asString()
        val otra = post(ORDENES, orden("importe" to "0.10"))["orden_id"].asString()

        val vista = vistaPrevia(VISTA_ORDENES, mapOf("ordenes" to listOf(una, otra)), cajero.token)

        assertTrue(vista["cobrable"].asBoolean(), vista.toString())
        assertTrue(vista["motivos"].isEmpty, vista.toString())
        assertEquals("150.60", vista["total"]["importe"].asString())
        assertEquals(hoy.toString(), vista["total"]["actualizado_a"].asString())
        assertEquals(listOf(una, otra), vista["lineas"].toList().map { it["orden_id"].asString() })
        assertEquals(listOf("150.50", "0.10"), vista["lineas"].toList().map { it["monto"]["importe"].asString() })
        // nada escrito: ni turno, ni recibo, y las órdenes siguen pendientes
        assertEquals(0, registros("turno", "caja" to caja.id).size)
        assertEquals("PENDIENTE", estadoDe(una))

        val recibo =
            post(
                COBROS,
                mapOf(
                    "caja" to caja.codigo,
                    "forma_pago" to "EFECTIVO",
                    "ordenes" to listOf(una, otra),
                    "observacion" to "cobro en ventanilla"
                ),
                cajero.token
            )["recibo"]
        assertEquals(vista["total"].toString(), recibo["total"].toString())
    }

    @Test
    fun `lo que impide cobrar unas ordenes va en motivos y no es un error`() {
        val pagada = post(ORDENES, orden())["orden_id"].asString()
        post(
            COBROS,
            mapOf("caja" to nuevaCaja().codigo, "forma_pago" to "EFECTIVO", "ordenes" to listOf(pagada), "observacion" to "cobro en ventanilla"),
            funcionario("CAJERO")
        )
        val futura = post(ORDENES, orden("fecha_exigibilidad" to hoy.plusDays(1).toString()))["orden_id"].asString()
        val falta = UUID.randomUUID().toString()

        val vista = vistaPrevia(VISTA_ORDENES, mapOf("ordenes" to listOf(pagada, futura, falta)), funcionario("CAJERO"))

        assertFalse(vista["cobrable"].asBoolean())
        val motivos = vista["motivos"].toList().map { it.asString() }
        assertEquals(3, motivos.size, motivos.toString())
        assertTrue(motivos[0].contains(falta), motivos.toString())
        assertTrue(motivos[1].contains(pagada) && motivos[1].contains("ya se cobró"), motivos.toString())
        assertTrue(motivos[2].contains(futura), motivos.toString())
        // las que existen salen con su monto
        assertEquals(listOf(pagada, futura), vista["lineas"].toList().map { it["orden_id"].asString() })

        val mercados = post(ORDENES, orden("sistema_origen" to "mercados"))["orden_id"].asString()
        val rentas = post(ORDENES, orden())["orden_id"].asString()
        val dosSistemas = vistaPrevia(VISTA_ORDENES, mapOf("ordenes" to listOf(rentas, mercados)), funcionario("CAJERO"))
        assertFalse(dosSistemas["cobrable"].asBoolean())
        assertTrue(dosSistemas["motivos"][0].asString().contains("«mercados»"), dosSistemas.toString())

        // ninguna que exista: sin líneas y sin total
        val ninguna = vistaPrevia(VISTA_ORDENES, mapOf("ordenes" to listOf(falta)), funcionario("CAJERO"))
        assertTrue(ninguna["total"].isNull, ninguna.toString())

        // la petición mal hecha sí es un 400
        rejected("POST", VISTA_ORDENES, mapOf("ordenes" to emptyList<String>()), "ordenes")
        rejected("POST", VISTA_ORDENES, mapOf("ordenes" to listOf(rentas), "tributo" to "PREDIAL"), "tributo")
        rejected("POST", VISTA_ORDENES, mapOf("ordenes" to listOf(rentas), "fecha_de_pago" to hoy.minusDays(1).toString()), "fecha_de_pago")
    }

    @Test
    fun `la vista previa de unas tasas da sus precios vigentes y el total del recibo que se emite despues`() {
        val caja = nuevaCaja()
        val cajero = cuenta("CAJERO")
        val constancia = codigoDeTasa()
        nuevaTasa(constancia, "10.00", hoy.minusDays(60), hoy.minusDays(1))
        nuevaTasa(constancia, "12.30", hoy)
        val copia = codigoDeTasa()
        nuevaTasa(copia, "0.10", hoy.minusDays(1))
        val conceptos = listOf(mapOf("codigo" to constancia, "cantidad" to 3), mapOf("codigo" to copia, "cantidad" to 7))

        val vista = vistaPrevia(VISTA_TASAS, mapOf("conceptos" to conceptos), cajero.token)

        assertTrue(vista["cobrable"].asBoolean(), vista.toString())
        assertEquals(listOf(constancia, copia), vista["lineas"].toList().map { it["codigo"].asString() })
        assertEquals(listOf("12.30", "0.10"), vista["lineas"].toList().map { it["precio_unitario"]["importe"].asString() })
        assertEquals(listOf("36.90", "0.70"), vista["lineas"].toList().map { it["monto"]["importe"].asString() })
        assertEquals("37.60", vista["total"]["importe"].asString())
        assertEquals(0, registros("turno", "caja" to caja.id).size)

        val recibo =
            post(
                "/api/caja/cobros/tasas",
                mapOf("caja" to caja.codigo, "forma_pago" to "EFECTIVO", "conceptos" to conceptos, "observacion" to "cobro de tasas en ventanilla"),
                cajero.token
            )["recibo"]
        assertEquals(vista["total"].toString(), recibo["total"].toString())

        // una sin tarifa vigente y una en cero van en motivos
        val enCero = codigoDeTasa()
        nuevaTasa(enCero, "0.00", hoy.minusDays(1))
        val conProblemas =
            vistaPrevia(
                VISTA_TASAS,
                mapOf("conceptos" to listOf(mapOf("codigo" to constancia), mapOf("codigo" to "T-NO-EXISTE"), mapOf("codigo" to enCero))),
                cajero.token
            )
        assertFalse(conProblemas["cobrable"].asBoolean())
        assertEquals(listOf(constancia), conProblemas["lineas"].toList().map { it["codigo"].asString() })
        assertEquals("12.30", conProblemas["total"]["importe"].asString())
        assertTrue(conProblemas["motivos"][0].asString().contains("'T-NO-EXISTE'"), conProblemas.toString())
        assertTrue(conProblemas["motivos"][1].asString().contains("tarifa en cero"), conProblemas.toString())

        rejected("POST", VISTA_TASAS, mapOf("conceptos" to listOf(mapOf("codigo" to constancia, "precio" to "1.00"))), "conceptos[0].precio")
        rejected("POST", VISTA_TASAS, mapOf("conceptos" to emptyList<Any>()), "conceptos")
    }

    @Test
    fun `la vista previa exige READ sobre orden_de_cobro y sobre tasa`() {
        val ordenId = post(ORDENES, orden())["orden_id"].asString()
        val codigo = codigoDeTasa()
        nuevaTasa(codigo, "12.30", hoy.minusDays(1))
        val sinLectura = funcionario(listOf(permiso("caja", "READ")))

        val ordenes = tree(send("POST", VISTA_ORDENES, mapOf("ordenes" to listOf(ordenId)), HttpStatus.FORBIDDEN, sinLectura))
        assertTrue(ordenes["detail"].asString().contains("orden_de_cobro"), ordenes.toString())
        val tasas = tree(send("POST", VISTA_TASAS, mapOf("conceptos" to listOf(mapOf("codigo" to codigo))), HttpStatus.FORBIDDEN, sinLectura))
        assertTrue(tasas["detail"].asString().contains("tasa"), tasas.toString())

        // TESORERIA lee las dos
        vistaPrevia(VISTA_ORDENES, mapOf("ordenes" to listOf(ordenId)), funcionario("TESORERIA"))
        vistaPrevia(VISTA_TASAS, mapOf("conceptos" to listOf(mapOf("codigo" to codigo))), funcionario("TESORERIA"))
    }

    private fun vistaPrevia(
        ruta: String,
        cuerpo: Map<String, Any?>,
        token: String
    ): JsonNode = tree(send("POST", ruta, cuerpo, HttpStatus.OK, token))

    private fun estadoDe(orden: String): String =
        tree(send("GET", "/api/objects/orden_de_cobro/records/$orden", null, HttpStatus.OK))["attributes"]["estado"].asString()

    private companion object {
        const val ORDENES = "/api/caja/ordenes-de-cobro"
        const val COBROS = "/api/caja/cobros"
        const val VISTA_ORDENES = "/api/caja/cobros/vista-previa"
        const val VISTA_TASAS = "/api/caja/cobros/tasas/vista-previa"
    }
}
