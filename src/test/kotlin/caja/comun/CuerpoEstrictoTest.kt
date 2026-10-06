package caja.comun

import caja.buzon.PeticionDeExplicacion
import caja.cobro.ConceptoPedido
import caja.cobro.NuevaOrden
import caja.cobro.NuevoCobro
import caja.cobro.NuevoCobroDeTasas
import caja.cobro.VistaPreviaDeOrdenes
import caja.cobro.VistaPreviaDeTasas
import caja.recibo.PeticionDeAnulacion
import caja.recibo.PeticionDeDuplicado
import caja.turno.PeticionDeCierre
import caja.turno.PeticionDeReversion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

// todo cuerpo que recibe la api de caja está en lista blanca: una clave que no es suya se anota en desconocidos, y las
// reglas de la petición la rechazan con un 400 que la nombra. un @RequestBody nuevo que no heredara de CuerpoEstricto
// ignoraría en silencio lo que no conoce, y el cliente creería que se guardó
class CuerpoEstrictoTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `todo cuerpo que recibe un controlador de caja es un cuerpo estricto`() {
        val controladores =
            ClassPathScanningCandidateComponentProvider(false)
                .apply { addIncludeFilter(AnnotationTypeFilter(RestController::class.java)) }
                .findCandidateComponents("caja")
                .map { Class.forName(it.beanClassName!!) }
        val cuerpos =
            controladores.flatMap { controlador ->
                controlador.declaredMethods.flatMap { metodo ->
                    metodo.parameters.filter { it.isAnnotationPresent(RequestBody::class.java) }.map { "${controlador.simpleName}.${metodo.name}" to it.type }
                }
            }
        assertTrue(cuerpos.size >= 10, "no se encontraron los cuerpos de la api de caja: $cuerpos")

        assertEquals(emptyList<String>(), cuerpos.filterNot { CuerpoEstricto::class.java.isAssignableFrom(it.second) }.map { it.first })
    }

    // la clave conocida de cada cuerpo es snake_case (de dos palabras donde las hay): si su @JsonNaming se perdiera, se
    // anotaría como desconocida
    @Test
    fun `una clave desconocida se anota, y una conocida no`() {
        mapOf(
            NuevaOrden::class.java to "sistema_origen",
            NuevoCobro::class.java to "forma_pago",
            NuevoCobroDeTasas::class.java to "pagador_documento",
            ConceptoPedido::class.java to "codigo",
            VistaPreviaDeOrdenes::class.java to "fecha_de_pago",
            VistaPreviaDeTasas::class.java to "fecha_de_cobro",
            PeticionDeCierre::class.java to "caja",
            PeticionDeReversion::class.java to "motivo",
            PeticionDeAnulacion::class.java to "autorizado_por",
            PeticionDeDuplicado::class.java to "observacion",
            PeticionDeExplicacion::class.java to "explicacion"
        ).forEach { (tipo, conocida) ->
            val cuerpo: CuerpoEstricto = json.readerFor(tipo).readValue("""{"$conocida": "x", "tributo": "PREDIAL", "precio": "1.00"}""")
            assertEquals(listOf("tributo", "precio"), cuerpo.desconocidos, tipo.simpleName)
        }
    }
}
