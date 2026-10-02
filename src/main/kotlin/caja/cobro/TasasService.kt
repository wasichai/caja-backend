package caja.cobro

import caja.comun.AREA
import caja.comun.Importe
import caja.comun.Observacion
import caja.comun.Permisos
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.TASA
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

// la caja de tasas (CobrarTasa de caja): cobra derechos del TUPA con el acto de ventanilla (Ventanilla), en la misma
// transacción y con el mismo turno, candados, idempotencia y numeración que el cobro de órdenes. el precio sale de la
// tasa vigente a la fecha del cobro, nunca de la petición (regla 5). no toca ninguna orden y no produce evento: el
// concepto es de la propia caja, no hay a quién avisarle. y la lista de las tasas vigentes y la vista previa
@Service
class TasasService(
    private val ventanilla: Ventanilla,
    private val registros: Registros,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val reloj: Clock
) {
    suspend fun cobrar(
        body: NuevoCobroDeTasas,
        idempotencia: String?
    ): CobroRespuesta {
        val usuario = currentUser.require()
        // los demás (turno, línea, lectura de tasa) los aplica core al leer y escribir
        permisos.exigir(usuario, "Cobrar tasas", "el cobro emite el recibo", Actions.CREATE to RECIBO)
        val cajero = cajeroDeLaSesion(body.cajero, usuario.email)
        val hoy = LocalDate.now(reloj)
        val pedido = pedido(body, idempotencia, cajero, hoy)
        return ventanilla.cobrar(
            pedido.apertura,
            preparar = {
                // las tarifas no llevan candado: son configuración, y la decisión es la tarifa vigente hoy
                val cotizacion = cotizar(pedido.conceptos, tasasDe(pedido.conceptos), hoy)
                cotizacion.impedimentos.firstOrNull()?.let { throw it }
                Ventanilla.Contenido(pedido.pagador, cotizacion.lineas.map(::lineaDeTasa), codigos(cotizacion.lineas.map { it.tasa }))
            },
            // sin evento: TipoDePago.produceEvento() es falso para TASA
            despues = { emitido, codigos -> respuestaDelCobro(emitido.recibo, emitido.lineas, null, emitido = true, codigos = codigos) },
            reenviado = { recibo, lineas ->
                val tasas = registros.byIds(TASA, Tasa::class.java, lineas.mapNotNull { it.tasa }).values.toList()
                respuestaDelCobro(recibo, lineas, null, emitido = false, codigos = codigos(tasas))
            }
        )
    }

    // las tasas vigentes a esa fecha (hoy en Lima si no viene), por código: la lista que ofrece la ventanilla, con el
    // precio a esa fecha. la vigencia la decide tarifaVigente, la misma regla del cobro. un código con una vigencia al
    // revés (un dato mal cargado en el admin) no tumba la lista: se omite, como el cobro lo rechazaría, y queda un WARN
    suspend fun vigentes(vigentesA: String?): List<TasaVigente> {
        val fecha = fechaDeVigencia(vigentesA)
        val vigentes =
            registros
                .all(TASA, Tasa::class.java, sort = "codigo")
                .groupBy { it.codigo!! }
                .mapNotNull { (codigo, tasas) ->
                    try {
                        tarifaVigente(tasas, fecha)
                    } catch (malCargada: ConflictException) {
                        log.warn("La tasa {} se omite de la lista de tasas vigentes: {}", codigo, malCargada.message)
                        null
                    }
                }
        val areas = registros.byIds(AREA, Area::class.java, vigentes.mapNotNull { it.area })
        return vigentes.map { tasa ->
            TasaVigente(tasa.codigo!!, tasa.descripcion, tasa.area?.let(areas::get)?.codigo, tasa.partidaPresupuestal, Importe.de(tasa.importe!!, fecha))
        }
    }

    // lo que costaría cobrar esos conceptos hoy, sin candados ni escritura: las mismas reglas que el cobro (cotizar,
    // lineaDeTasa, totalDe), y lo que lo impediría va en motivos
    suspend fun vistaPrevia(body: VistaPreviaDeTasas): VistaPrevia {
        val usuario = currentUser.require()
        permisos.exigir(usuario, "La vista previa", "lee las tarifas vigentes", Actions.READ to TASA)
        val hoy = LocalDate.now(reloj)
        val errores = mutableListOf<FieldViolation>()
        campo(errores) { sinPrecioNiCamposDesconocidos(body.desconocidos) }
        val conceptos = campo(errores) { conceptosPedidos(body.conceptos) }
        campo(errores) { fechaDePago(body.fechaDeCobro, hoy, "fecha_de_cobro") }
        if (errores.isNotEmpty()) throw ValidationException("La vista previa no es válida", errores)

        val cotizacion = cotizar(conceptos!!, tasasDe(conceptos), hoy)
        return vistaPrevia(cotizacion.lineas.map(::lineaDeTasa), hoy, cotizacion.impedimentos, codigos(cotizacion.lineas.map { it.tasa }))
    }

    // todas las vigencias de cada código pedido: la vigente la elige tarifaVigente
    private suspend fun tasasDe(conceptos: List<LineaDeTasaPedida>): Map<String, List<Tasa>> =
        conceptos
            .map { it.codigo }
            .distinct()
            .associateWith { registros.all(TASA, Tasa::class.java, filters = mapOf("codigo" to it)) }

    private fun codigos(tasas: List<Tasa>): Map<String, String> = tasas.associate { it.id!! to it.codigo!! }

    // las reglas sobre la petición, con todos los campos que fallan en un solo 400
    private fun pedido(
        body: NuevoCobroDeTasas,
        idempotencia: String?,
        cajero: String,
        hoy: LocalDate
    ): Pedido {
        val errores = mutableListOf<FieldViolation>()
        campo(errores) { sinPrecioNiCamposDesconocidos(body.desconocidos) }
        val clave = campo(errores) { claveDeIdempotencia(idempotencia) }
        val caja = campo(errores) { codigoDeCaja(body.caja) }
        val forma = campo(errores) { formaDePago(body.formaPago) }
        campo(errores) { fechaDePago(body.fechaDeCobro, hoy, "fecha_de_cobro") }
        val pagador = campo(errores) { pagador(body.pagadorDocumento, body.pagadorNombre, body.pagadorExternoId) }
        val conceptos = campo(errores) { conceptosPedidos(body.conceptos) }
        val observacion = campo(errores) { Observacion.de(body.observacion) }
        if (errores.isNotEmpty()) throw ValidationException("El cobro de tasas no es válido", errores)
        return Pedido(Ventanilla.Apertura(caja!!, cajero, hoy, forma!!, observacion!!, clave, PAGO_DE_TASA), pagador!!, conceptos!!)
    }

    // vigentes_a: una fecha cualquiera (consultar no cobra), hoy en Lima si no viene
    private fun fechaDeVigencia(valor: String?): LocalDate {
        val texto = valor?.trim()?.ifEmpty { null } ?: return LocalDate.now(reloj)
        return try {
            LocalDate.parse(texto)
        } catch (_: DateTimeParseException) {
            throw ValidationException("Fecha inválida", "vigentes_a", "una fecha AAAA-MM-DD")
        }
    }

    private class Pedido(
        val apertura: Ventanilla.Apertura,
        val pagador: Pagador,
        val conceptos: List<LineaDeTasaPedida>
    )

    private companion object {
        val log = LoggerFactory.getLogger(TasasService::class.java)
    }
}
