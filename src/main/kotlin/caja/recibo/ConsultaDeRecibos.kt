package caja.recibo

import caja.cobro.Caja
import caja.cobro.LineaRecibo
import caja.cobro.Recibo
import caja.cobro.Tasa
import caja.cobro.lineaRespuesta
import caja.cobro.lineasEnOrden
import caja.comun.ANULACION_RECIBO
import caja.comun.CAJA
import caja.comun.Importe
import caja.comun.LIMA
import caja.comun.LINEA_RECIBO
import caja.comun.RECIBO
import caja.comun.REIMPRESION_RECIBO
import caja.comun.Registros
import caja.comun.TASA
import caja.comun.Transaccion
import caja.comun.campo
import caja.comun.diaPedido
import caja.comun.enLima
import caja.comun.rangoDeDias
import org.springframework.stereotype.Service
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

// la página dentro del tramo ordenado: empieza donde la deja el desplazamiento, descontados los más recientes que el
// tramo. con una sola foto de la base, desplazamiento >= masRecientes siempre; si no (una lectura descuadrada), empieza
// en 0 en vez de reventar
internal fun <T> paginaDelTramo(
    tramo: List<T>,
    desplazamiento: Long,
    masRecientes: Long,
    tamano: Int
): List<T> = tramo.drop((desplazamiento - masRecientes).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()).take(tamano)

// la consulta de recibos (ConsultaDeRecibos y la vista previa de DuplicadoDeRecibo de caja): el listado para quien
// perdió el papel y la ficha de un recibo por su número. no escribe ni recalcula nada: cada cifra es la que el recibo
// congeló, con su fecha (regla 9), y el estado y los duplicados se derivan de la anulación y las reimpresiones. todo se
// lee como el usuario que llama: sin READ sobre recibo, el 403 de core
@Service
class ConsultaDeRecibos(
    private val registros: Registros,
    private val transaccion: Transaccion,
    private val metadata: MetadataService,
    private val schemas: WasichaiSchemas
) {
    // la página de recibos que piden los filtros, del más reciente al más antiguo. desde y hasta son días de Lima sobre
    // emitido_en, los dos incluidos; estado es EMITIDO o ANULADO. una búsqueda sin resultados es una página vacía, no
    // un 404
    suspend fun listar(
        documento: String?,
        caja: String?,
        cajero: String?,
        desde: String?,
        hasta: String?,
        estado: String?,
        page: Int?,
        size: Int?
    ): PageResponse<ReciboEnLista> {
        val errores = mutableListOf<FieldViolation>()
        val primerDia = campo(errores) { diaPedido(desde, "desde") }
        val ultimoDia = campo(errores) { diaPedido(hasta, "hasta") }
        campo(errores) { rangoDeDias(primerDia, ultimoDia) }
        val estadoFiltrado = campo(errores) { estadoPedido(estado) }
        if (errores.isNotEmpty()) throw ValidationException("La consulta de recibos no es válida", errores)

        val pedida = PageRequest.of(page, size)
        val filtros =
            buildMap {
                documento?.trim()?.ifEmpty { null }?.let { put("pagador_documento", it.uppercase(Locale.ROOT)) }
                // el cajero es el correo de la sesión, tal cual: en mayúsculas no encontraría nunca sus recibos
                cajero?.trim()?.ifEmpty { null }?.let { put("cajero", it) }
                caja?.trim()?.ifEmpty { null }?.let { codigo ->
                    val id =
                        registros.primero(CAJA, Caja::class.java, mapOf("codigo" to codigo))?.id
                            ?: return PageResponse.of(emptyList(), pedida.page, pedida.size, 0)
                    put("caja", id)
                }
            }
        val criterios =
            buildList {
                primerDia?.let { dia -> add(emitido(">=", inicioDe(dia))) }
                ultimoDia?.let { dia -> add(emitido("<", inicioDe(dia.plusDays(1)))) }
                estadoFiltrado?.let { add(conAnulacion(it == ANULADO, metadata.definitionOf(ANULACION_RECIBO))) }
            }

        // la página, su desempate, las anulaciones y las reimpresiones se leen en UNA foto de la base (REPEATABLE READ):
        // un cobro o una anulación que se confirme entre dos lecturas no las descuadra
        val (pagina, recibos, anulados, duplicados) =
            transaccion.lectura {
                val pagina =
                    registros.page(
                        RECIBO,
                        Recibo::class.java,
                        RecordQuery(page = pedida, sort = "emitido_en", descending = true, filters = filtros, criteria = criterios)
                    )
                val recibos = desempatada(pagina.content, pedida, filtros, criterios)
                val ids = recibos.map { it.id!! }
                Leido(
                    pagina,
                    recibos,
                    registros.byRelation(ANULACION_RECIBO, AnulacionRecibo::class.java, "recibo", ids).mapTo(HashSet()) { it.recibo },
                    registros.byRelation(REIMPRESION_RECIBO, ReimpresionRecibo::class.java, "recibo", ids).groupingBy { it.recibo }.eachCount()
                )
            }
        return PageResponse(
            recibos.map { recibo ->
                ReciboEnLista(
                    numeroImpreso = recibo.numeroImpreso!!,
                    emitidoEn = enLima(recibo.emitidoEn!!),
                    pagadorDocumento = recibo.pagadorDocumento,
                    pagadorNombre = recibo.pagadorNombre,
                    total = Importe.de(recibo.total!!, recibo.actualizadoA!!),
                    formaPago = recibo.formaPago!!,
                    duplicados = (duplicados[recibo.id] ?: 0).toLong(),
                    estado = estadoDelRecibo(recibo.id in anulados)
                )
            },
            pagina.page,
            pagina.size,
            pagina.totalElements,
            pagina.totalPages
        )
    }

    // la ficha: el recibo con sus líneas en el orden del papel, su estado, sus duplicados y su anulación. 404 si no
    // existe
    suspend fun ficha(numeroImpreso: String): ReciboEnFicha {
        val numero = numeroDeRecibo(numeroImpreso)
        val recibo =
            registros.primero(RECIBO, Recibo::class.java, mapOf("numero_impreso" to numero))
                ?: throw NotFoundException("No hay ningún recibo $numero")
        val reciboId = recibo.id!!
        val lineas = registros.all(LINEA_RECIBO, LineaRecibo::class.java, filters = mapOf("recibo" to reciboId))
        val codigos = registros.byIds(TASA, Tasa::class.java, lineas.mapNotNull { it.tasa }).mapValues { it.value.codigo!! }
        val caja = registros.byIds(CAJA, Caja::class.java, listOfNotNull(recibo.caja))[recibo.caja]
        val anulacion = registros.primero(ANULACION_RECIBO, AnulacionRecibo::class.java, mapOf("recibo" to reciboId))
        val fecha = recibo.actualizadoA!!
        return ReciboEnFicha(
            numeroImpreso = recibo.numeroImpreso!!,
            serie = recibo.serie!!,
            numero = recibo.numero!!,
            caja = caja?.codigo,
            cajero = recibo.cajero!!,
            emitidoEn = enLima(recibo.emitidoEn!!),
            pagadorDocumento = recibo.pagadorDocumento,
            pagadorNombre = recibo.pagadorNombre,
            pagadorExternoId = recibo.pagadorExternoId,
            formaPago = recibo.formaPago!!,
            tipoPago = recibo.tipoPago!!,
            total = Importe.de(recibo.total!!, fecha),
            observacion = recibo.observacion,
            lineas = lineasEnOrden(lineas).map { lineaRespuesta(it, fecha, codigos) },
            estado = estadoDelRecibo(anulacion != null),
            duplicados = registros.count(REIMPRESION_RECIBO, mapOf("recibo" to reciboId)),
            anulacion =
                anulacion?.let {
                    AnulacionEnFicha(it.fecha.toString(), it.motivo!!, it.autorizadoPor, it.documentoAutorizacion, it.usuario!!)
                }
        )
    }

    // el ORDER BY de core es de una sola columna: dos recibos del mismo instante podrían salir en un orden distinto en
    // cada página, y uno repetirse y otro perderse al pasar de página. el desempate es por id, descendente: se leen los
    // recibos entre el instante del último de la página y el del primero (los empatados de los bordes incluidos) y se
    // cuentan los más recientes que el primero; la página es el tramo de esa lista, en el orden (emitido_en, id), que
    // empieza en su desplazamiento
    private suspend fun desempatada(
        leidos: List<Recibo>,
        pedida: PageRequest,
        filtros: Map<String, String>,
        criterios: List<RecordCriterion>
    ): List<Recibo> {
        if (leidos.isEmpty()) return leidos
        val primero = leidos.first().emitidoEn!!
        val ultimo = leidos.last().emitidoEn!!
        val masRecientes = registros.count(RECIBO, filtros, criterios + emitido(">", primero.atOffset(ZoneOffset.UTC)))
        val tramo =
            registros
                .all(
                    RECIBO,
                    Recibo::class.java,
                    filtros,
                    criteria = criterios + emitido(">=", ultimo.atOffset(ZoneOffset.UTC)) + emitido("<=", primero.atOffset(ZoneOffset.UTC))
                ).sortedWith(compareByDescending<Recibo> { it.emitidoEn }.thenByDescending { it.id })
        return paginaDelTramo(tramo, pedida.offset, masRecientes, leidos.size)
    }

    private data class Leido(
        val pagina: PageResponse<Recibo>,
        val recibos: List<Recibo>,
        val anulados: Set<String?>,
        val duplicados: Map<String?, Int>
    )

    // emitido_en comparado con un instante, que va enlazado
    private fun emitido(
        operador: String,
        instante: OffsetDateTime
    ) = RecordCriterion { recibo, bind -> "${columna(recibo, "emitido_en")} $operador ${bind(instante)}" }

    // el estado se deriva de que exista la anulación (V30 de caja): EXISTS sobre la tabla de anulacion_recibo, de la
    // misma organización y que nombre a este recibo. es una subconsulta y no una lista de ids, que crecería sin fin
    private fun conAnulacion(
        anulado: Boolean,
        anulacion: ObjectDefinition
    ) = RecordCriterion { recibo, _ ->
        val fuera = schemas.dataTable(recibo.obj.physicalTable)
        val dentro = schemas.dataTable(anulacion.obj.physicalTable)
        val existe =
            "EXISTS (SELECT 1 FROM $dentro a WHERE a.organization_id = $fuera.organization_id " +
                "AND a.${columna(anulacion, "recibo")} = $fuera.id)"
        if (anulado) existe else "NOT $existe"
    }

    private fun columna(
        definicion: ObjectDefinition,
        campo: String
    ): String = SqlIdentifier.quote(definicion.fields.first { it.name == campo }.columnName)

    private companion object {
        // la medianoche de Lima de ese día, como instante: el día de Lima, no el de la máquina ni el de la sesión
        fun inicioDe(dia: LocalDate): OffsetDateTime = dia.atStartOfDay(LIMA).toOffsetDateTime()
    }
}
