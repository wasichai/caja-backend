package caja.turno

import caja.cobro.FORMAS_DE_PAGO
import caja.comun.Importe
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// las formas del turno, su cierre y su reversión. claves snake_case, las de los campos del modelo

// el cierre, sus líneas y la reversión como los guarda core. turno y cierre_turno son ids de relación
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CierreTurno(
    val id: String? = null,
    val turno: String? = null,
    val secuencia: Long? = null,
    val fecha: LocalDate? = null,
    val registradoEn: Instant? = null,
    val totalCobrado: BigDecimal? = null,
    val totalAnulado: BigDecimal? = null,
    val neto: BigDecimal? = null,
    val totalDeclarado: BigDecimal? = null,
    val diferencia: BigDecimal? = null,
    val recibosEmitidos: Long? = null,
    val recibosAnulados: Long? = null,
    val cobradoConEvento: BigDecimal? = null,
    val cobradoSinEvento: BigDecimal? = null,
    val usuario: String? = null,
    val observacion: String? = null,
    val claveSecuencia: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CierreTurnoLinea(
    val id: String? = null,
    val cierreTurno: String? = null,
    val formaPago: String? = null,
    val cobrado: BigDecimal? = null,
    val anulado: BigDecimal? = null,
    val neto: BigDecimal? = null,
    val declarado: BigDecimal? = null,
    val clave: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReversionCierre(
    val id: String? = null,
    val turno: String? = null,
    val cierreRevertido: String? = null,
    val secuencia: Long? = null,
    val motivo: String? = null,
    val fecha: LocalDate? = null,
    val registradoEn: Instant? = null,
    val usuario: String? = null,
    val observacion: String? = null,
    val claveSecuencia: String? = null
)

// lo que manda la ventanilla para cerrar: la caja, lo contado por forma de pago (en cadena: regla 1) y por qué. el
// cajero y la fecha salen de la sesión y del reloj; si vienen, el cajero tiene que ser el de la sesión y la fecha, hoy
// o un día pasado. una clave desconocida se anota y se rechaza
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class PeticionDeCierre(
    val caja: String? = null,
    val cajero: String? = null,
    val fecha: String? = null,
    val declarado: Map<String, String?>? = null,
    val observacion: String? = null
) {
    @JsonIgnore
    val desconocidos: MutableList<String> = mutableListOf()

    @JsonAnySetter
    fun desconocido(
        nombre: String,
        @Suppress("UNUSED_PARAMETER") valor: Any?
    ) {
        desconocidos += nombre
    }
}

// lo que manda la ventanilla para reversar el cierre vigente de su turno: la caja, el motivo y por qué se registra
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class PeticionDeReversion(
    val caja: String? = null,
    val cajero: String? = null,
    val fecha: String? = null,
    val motivo: String? = null,
    val observacion: String? = null
) {
    @JsonIgnore
    val desconocidos: MutableList<String> = mutableListOf()

    @JsonAnySetter
    fun desconocido(
        nombre: String,
        @Suppress("UNUSED_PARAMETER") valor: Any?
    ) {
        desconocidos += nombre
    }
}

// los turnos de hoy de quien pregunta, con su situación. sin ninguno es una respuesta (SIN_ABRIR, turnos vacío), no un
// error: a las ocho de la mañana todos están así, porque el turno lo abre el primer cobro
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TurnoDelDia(
    val cajero: String,
    val fecha: String,
    val situacion: SituacionDelCajero,
    val turnos: List<TurnoEnElDia>
)

// un turno con su caja (el código y el nombre), la hora a la que se abrió (en Lima) y su estado, derivado
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TurnoEnElDia(
    val turnoId: String,
    val caja: String?,
    val cajaNombre: String?,
    val cajero: String,
    val fecha: String,
    val abiertoEn: String,
    val estadoDelTurno: EstadoDelTurno
)

// una línea del arqueo como sale por la api. en el arqueo en vivo, declarado y diferencia van en null: un GET no lleva
// el recuento del cajón, y un 0 se leería como «se contó cero»
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LineaDeArqueoRespuesta(
    val formaPago: String,
    val cobrado: Importe,
    val anulado: Importe,
    val neto: Importe,
    val declarado: Importe?,
    val diferencia: Importe?
)

// el arqueo como sale por la api, con cada cifra a la fecha a la que se leyó (regla 9). en vivo, total_declarado,
// diferencia y cuadra van en null: nadie ha contado
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ArqueoRespuesta(
    val lineas: List<LineaDeArqueoRespuesta>,
    val recibosEmitidos: Int,
    val recibosAnulados: Int,
    val totalCobrado: Importe,
    val totalAnulado: Importe,
    val neto: Importe,
    val totalDeclarado: Importe?,
    val diferencia: Importe?,
    val cuadra: Boolean?
) {
    companion object {
        // el arqueo que se mira sin escribir nada: sin lo declarado
        fun enVivo(arqueo: ArqueoDelTurno): ArqueoRespuesta = de(arqueo, declarado = false)

        // el arqueo de un cierre, con lo que el cajero declaró
        fun declarado(arqueo: ArqueoDelTurno): ArqueoRespuesta = de(arqueo, declarado = true)

        // el arqueo de un acta ya firmada, con las cifras que congeló y a su fecha: NO se vuelve a calcular con los
        // recibos de hoy. la diferencia de cada línea es su declarado menos su neto guardados (el acta no guarda esa
        // resta por línea, sí la del total), y cuadra es que la diferencia guardada sea cero. las líneas, en el orden
        // del enumerado, como las dibuja el cierre
        fun delActa(
            acta: CierreTurno,
            lineas: List<CierreTurnoLinea>
        ): ArqueoRespuesta {
            val fecha = acta.fecha!!

            fun importe(cifra: BigDecimal) = Importe.de(cifra, fecha)
            return ArqueoRespuesta(
                lineas =
                    lineas.sortedBy { FORMAS_DE_PAGO.indexOf(it.formaPago) }.map {
                        LineaDeArqueoRespuesta(
                            it.formaPago!!,
                            importe(it.cobrado!!),
                            importe(it.anulado!!),
                            importe(it.neto!!),
                            importe(it.declarado!!),
                            importe(it.declarado.subtract(it.neto))
                        )
                    },
                recibosEmitidos = acta.recibosEmitidos!!.toInt(),
                recibosAnulados = acta.recibosAnulados!!.toInt(),
                totalCobrado = importe(acta.totalCobrado!!),
                totalAnulado = importe(acta.totalAnulado!!),
                neto = importe(acta.neto!!),
                totalDeclarado = importe(acta.totalDeclarado!!),
                diferencia = importe(acta.diferencia!!),
                cuadra = acta.diferencia.signum() == 0
            )
        }

        private fun de(
            arqueo: ArqueoDelTurno,
            declarado: Boolean
        ): ArqueoRespuesta {
            val fecha = arqueo.aLaFecha

            fun importe(cifra: BigDecimal) = Importe.de(cifra, fecha)

            fun siDeclarado(cifra: BigDecimal) = if (declarado) importe(cifra) else null
            return ArqueoRespuesta(
                lineas =
                    arqueo.lineas.map {
                        LineaDeArqueoRespuesta(
                            it.formaPago,
                            importe(it.cobrado),
                            importe(it.anulado),
                            importe(it.neto),
                            siDeclarado(it.declarado),
                            siDeclarado(it.diferencia)
                        )
                    },
                recibosEmitidos = arqueo.recibosEmitidos,
                recibosAnulados = arqueo.recibosAnulados,
                totalCobrado = importe(arqueo.totalCobrado),
                totalAnulado = importe(arqueo.totalAnulado),
                neto = importe(arqueo.neto),
                totalDeclarado = siDeclarado(arqueo.totalDeclarado),
                diferencia = siDeclarado(arqueo.diferencia),
                cuadra = if (declarado) arqueo.cuadra() else null
            )
        }
    }
}

// si el turno puede cerrar ahora y, si no, por qué (EstadoDelCierreController de caja): el arqueo en vivo, las dos
// mitades del cuadre y los pagos que impiden cerrar, uno a uno. puede_cerrar es falso con el turno ya cerrado o con un
// pago sin entregar
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ArqueoDelTurnoRespuesta(
    val turnoId: String,
    val estadoDelTurno: EstadoDelTurno,
    val puedeCerrar: Boolean,
    val arqueo: ArqueoRespuesta,
    val cobradoConEvento: Importe,
    val cobradoSinEvento: Importe,
    val loQueImpideCerrar: List<PagoSinEntregar>,
    // con el turno CERRADO, el acta de su cierre vigente: lo que se contó al cerrar, tal como se firmó, para que la
    // pantalla lo muestre después de recargar sin recalcular ni restar nada. con el turno abierto, null
    val cierreVigente: CierreVigente?
)

// el acta del cierre vigente de un turno, como se guardó: su secuencia, cuándo y quién la firmó, su arqueo declarado
// (líneas con declarado y diferencia, totales, diferencia y cuadra) y las dos mitades del cuadre, todo a la fecha del
// turno
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CierreVigente(
    val cierreId: String,
    val secuencia: Long,
    val fecha: String,
    val registradoEn: String,
    val usuario: String,
    val observacion: String,
    val arqueo: ArqueoRespuesta,
    val cobradoConEvento: Importe,
    val cobradoSinEvento: Importe
) {
    companion object {
        fun de(
            acta: CierreTurno,
            lineas: List<CierreTurnoLinea>,
            registradoEn: String
        ) = CierreVigente(
            cierreId = acta.id!!,
            secuencia = acta.secuencia!!,
            fecha = acta.fecha!!.toString(),
            registradoEn = registradoEn,
            usuario = acta.usuario!!,
            observacion = acta.observacion!!,
            arqueo = ArqueoRespuesta.delActa(acta, lineas),
            cobradoConEvento = Importe.de(acta.cobradoConEvento!!, acta.fecha),
            cobradoSinEvento = Importe.de(acta.cobradoSinEvento!!, acta.fecha)
        )
    }
}

// el acta del cierre: el turno queda CERRADO, con su arqueo declarado y las dos mitades del cuadre
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CierreRespuesta(
    val cierreId: String,
    val turnoId: String,
    val caja: String,
    val cajero: String,
    val fecha: String,
    val secuencia: Long,
    val registradoEn: String,
    val usuario: String,
    val observacion: String,
    val estadoDelTurno: EstadoDelTurno,
    val arqueo: ArqueoRespuesta,
    val cobradoConEvento: Importe,
    val cobradoSinEvento: Importe
)

// la reversión: el cierre que deja sin efecto sigue donde estaba, y el turno queda ABIERTO
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReversionRespuesta(
    val reversionId: String,
    val turnoId: String,
    val caja: String,
    val cajero: String,
    val fecha: String,
    val secuencia: Long,
    val cierreRevertido: String,
    val motivo: String,
    val registradoEn: String,
    val usuario: String,
    val observacion: String,
    val estadoDelTurno: EstadoDelTurno
)
