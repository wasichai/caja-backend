package caja.cobro

import caja.comun.ANULACION_RECIBO
import caja.comun.CAJA
import caja.comun.Candado
import caja.comun.Candados
import caja.comun.LINEA_RECIBO
import caja.comun.Observacion
import caja.comun.RECIBO
import caja.comun.Registros
import caja.comun.TURNO
import caja.comun.Transaccion
import caja.turno.LibroDelTurno
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale

// el acto de ventanilla, común al cobro de órdenes y al de tasas: en UNA transacción, el turno, el número de la serie,
// el recibo y sus líneas, y lo que cada cobro agrega (las órdenes PAGADA y su evento; las tasas, nada). se confirma todo
// junto o no queda nada. el dinero entra por la misma ventanilla: la numeración y el turno son los mismos.
//
// wasichai no bloquea filas: cada decisión se toma bajo un candado consultivo de la transacción (Candados), con lo
// leído DESPUÉS de tomarlo. el orden de los candados es siempre el mismo, para que dos cobros no se esperen en cruz:
// TURNO_CLAVE <caja|cajero|fecha> → TURNO <id del turno> → (los que tome el cobro, en preparar: ORDEN <id> de cada
// orden, ordenadas por id) → SERIE <serie de la caja>, cada clase en su espacio (Candado). la unicidad de wasichai es
// la red: la uniqueConstraint compuesta (caja, cajero, fecha) del turno y los unique de numero_impreso,
// clave_idempotencia y evento_id. si una salta (DuplicateKeyException), la transacción entera se revierte y el cobro
// contesta 409, sin datos a medias y sin reintentar dentro (postgres no deja leer nada en una transacción abortada).
// cualquier otra violación de integridad sigue su camino como lo que es.
//
// el turno cerrado no cobra: se mira bajo el candado del turno, el mismo que toma el cierre (caja.turno.CerrarTurno).
// el reenvío de un intento ya emitido se contesta antes de todo eso, con una lectura por su clave.
//
// todo pasa por RecordService como el usuario que llama: sus permisos son los de core
@Component
class Ventanilla(
    private val registros: Registros,
    private val candados: Candados,
    private val transaccion: Transaccion,
    private val libro: LibroDelTurno,
    private val reloj: Clock
) {
    // lo que todo cobro trae, ya validado: la caja por su código, quién y cuándo, cómo se paga, por qué, la clave del
    // intento y el tipo de pago del recibo
    class Apertura(
        val caja: String,
        val cajero: String,
        val hoy: LocalDate,
        val formaPago: String,
        val observacion: Observacion,
        val clave: String?,
        val tipoPago: String
    )

    // lo que el cobro decide bajo sus candados: el pagador, las líneas sin recibo (con su monto) y lo que necesitará
    // después de emitir
    class Contenido<D>(
        val pagador: Pagador,
        val lineas: List<LineaRecibo>,
        val datos: D
    )

    // el recibo recién emitido con sus líneas guardadas
    class Emitido(
        val recibo: Recibo,
        val lineas: List<LineaRecibo>,
        val turno: String,
        val observacion: String
    )

    // preparar: lo propio de cada cobro antes del número (sus candados, sus lecturas y sus 404, 400 y 409), en el lugar
    // que le toca en el orden de los candados. despues: lo que cada cobro agrega al recibo emitido, en la misma
    // transacción. reenviado: la respuesta de un reenvío de la misma clave, con el recibo de la primera vez y sus líneas
    suspend fun <D, R> cobrar(
        apertura: Apertura,
        preparar: suspend () -> Contenido<D>,
        despues: suspend (Emitido, D) -> R,
        reenviado: suspend (Recibo, List<LineaRecibo>) -> R
    ): R {
        // el reenvío de un intento ya emitido se contesta ANTES de la transacción: antes de abrir o crear el turno y de
        // mirar si la caja sigue activa. un recibo confirmado no cambia, así que basta una lectura por su clave: el reenvío
        // del día siguiente no abre un turno vacío, y el de después de dar de baja la caja devuelve el recibo de la
        // primera vez. la carrera de dos primeras peticiones con la misma clave la resuelve, dentro, el candado del turno
        apertura.clave
            ?.let { registros.primero(RECIBO, Recibo::class.java, mapOf("clave_idempotencia" to it)) }
            ?.let { recibo ->
                val caja = registros.primero(CAJA, Caja::class.java, mapOf("codigo" to apertura.caja))
                return reenvio(recibo, apertura, caja?.id, reenviado)
            }
        return try {
            transaccion.en { emitir(apertura, preparar, despues, reenviado) }
        } catch (choque: DuplicateKeyException) {
            // la red de un unique saltó: otro cobro se confirmó a la vez. la transacción ya se revirtió entera
            throw ConflictException("El cobro chocó con otro que se confirmó a la vez y no se emitió nada: vuelva a intentarlo")
                .apply { initCause(choque) }
        }
    }

    // dentro de la transacción, en el orden de los candados
    private suspend fun <D, R> emitir(
        apertura: Apertura,
        preparar: suspend () -> Contenido<D>,
        despues: suspend (Emitido, D) -> R,
        reenviado: suspend (Recibo, List<LineaRecibo>) -> R
    ): R {
        val caja = cajaQueCobra(apertura.caja)
        val cajaId = caja.id!!

        // 1. el turno: el primer cobro del día lo abre, una vez. bajo su candado se busca y, si no está, se crea
        val claveTurno = claveDelTurno(cajaId, apertura.cajero, apertura.hoy)
        candados.bloquear(Candado.TURNO_CLAVE, claveTurno)
        val turno =
            registros.primero(TURNO, Turno::class.java, filtroDelTurno(cajaId, apertura.cajero, apertura.hoy))
                ?: registros.create(
                    TURNO,
                    Turno::class.java,
                    mapOf(
                        "caja" to cajaId,
                        "cajero" to apertura.cajero,
                        "fecha" to apertura.hoy.toString(),
                        "abierto_en" to OffsetDateTime.now(reloj).toString(),
                        "observacion" to apertura.observacion.texto
                    ),
                    apertura.observacion.texto
                )
        val turnoId = turno.id!!

        // 2. el candado del turno: el mismo que toman la anulación y el cierre, para que un cobro no se cuele en un
        // cierre en curso
        candados.bloquear(Candado.TURNO, turnoId)

        // 3. el reenvío del mismo intento, otra vez bajo el candado del turno: dos primeras peticiones con la misma clave
        // se ordenan, y la segunda devuelve el recibo de la primera
        apertura.clave?.let { clave ->
            registros.primero(RECIBO, Recibo::class.java, mapOf("clave_idempotencia" to clave))?.let { recibo ->
                return reenvio(recibo, apertura, cajaId, reenviado)
            }
        }

        // 4. el turno cerrado no cobra (TurnoCerrado de caja): su arqueo está firmado y este dinero no estaría en él. se
        // mira bajo su candado, el que toma el cierre: un cobro que esperaba a un cierre en curso lo encuentra cerrado
        libro.exigirAbierto(turno, "no se cobra en él")

        // 5. lo propio del cobro: sus candados, sus lecturas y sus rechazos
        val contenido = preparar()

        // 6. el número: el siguiente de la serie, bajo su candado. no deja huecos: si algo falla después, el recibo no
        // se confirma y el número vuelve a estar libre
        val serie = caja.serie!!.trim().uppercase(Locale.ROOT)
        candados.bloquear(Candado.SERIE, serie)
        val ultimo = registros.primero(RECIBO, Recibo::class.java, mapOf("serie" to serie), sort = "numero", descending = true)
        val numero = (ultimo?.numero ?: 0) + 1

        // 7. el recibo y sus líneas. el total es la suma exacta de las líneas; actualizado_a, la fecha del cobro (regla 9,
        // CobrarOrdenes.java:180; en las tasas, la fecha a la que la tarifa estaba vigente)
        val recibo =
            registros.create(
                RECIBO,
                Recibo::class.java,
                mapOf(
                    "serie" to serie,
                    "numero" to numero,
                    "numero_impreso" to numeroImpreso(serie, numero),
                    "caja" to cajaId,
                    "turno" to turnoId,
                    "cajero" to apertura.cajero,
                    "pagador_documento" to contenido.pagador.documento,
                    "pagador_nombre" to contenido.pagador.nombre,
                    "pagador_externo_id" to contenido.pagador.idExterno,
                    "emitido_en" to OffsetDateTime.now(reloj).toString(),
                    "forma_pago" to apertura.formaPago,
                    "tipo_pago" to apertura.tipoPago,
                    "total" to totalDe(contenido.lineas.map { it.monto!! }).toPlainString(),
                    "actualizado_a" to apertura.hoy.toString(),
                    "clave_idempotencia" to apertura.clave,
                    "observacion" to apertura.observacion.texto
                ),
                apertura.observacion.texto
            )
        val reciboId = recibo.id!!
        val lineas =
            contenido.lineas.map { linea ->
                registros.create(
                    LINEA_RECIBO,
                    LineaRecibo::class.java,
                    mapOf(
                        "recibo" to reciboId,
                        "orden" to linea.orden,
                        "tasa" to linea.tasa,
                        "sistema_origen" to linea.sistemaOrigen,
                        "concepto" to linea.concepto,
                        "detalle" to linea.detalle,
                        "referencia_externa" to linea.referenciaExterna,
                        "cantidad" to linea.cantidad,
                        "precio_unitario" to linea.precioUnitario?.toPlainString(),
                        "monto" to linea.monto!!.toPlainString()
                    ),
                    apertura.observacion.texto
                )
            }

        // 8. lo que el cobro agrega, en la misma transacción
        return despues(Emitido(recibo, lineas, turnoId, apertura.observacion.texto), contenido.datos)
    }

    // el reenvío de un intento: la clave es del cajero que la mandó, en esa caja y para ese tipo de cobro (la de otro
    // cobro es un choque, no un reenvío), y su recibo tiene que seguir en pie: devolver el de un recibo anulado como un
    // cobro exitoso dejaría al cliente creyendo que cobró
    private suspend fun <R> reenvio(
        recibo: Recibo,
        apertura: Apertura,
        cajaId: String?,
        reenviado: suspend (Recibo, List<LineaRecibo>) -> R
    ): R {
        if (recibo.cajero != apertura.cajero || recibo.caja != cajaId || recibo.tipoPago != apertura.tipoPago) {
            throw ConflictException("La Idempotency-Key ya nombra el recibo ${recibo.numeroImpreso} de otro cobro: mande una nueva")
        }
        if (registros.count(ANULACION_RECIBO, mapOf("recibo" to recibo.id!!)) > 0) {
            throw ConflictException(
                "No se devuelve como cobrado: el recibo de ese cobro está anulado. La Idempotency-Key nombra el recibo " +
                    "${recibo.numeroImpreso}, que se anuló, y no se cobró otra vez; para cobrar de nuevo, mande una Idempotency-Key nueva"
            )
        }
        return reenviado(recibo, registros.all(LINEA_RECIBO, LineaRecibo::class.java, filters = mapOf("recibo" to recibo.id)))
    }

    // la caja por su código: 404 si no existe, 409 si se dio de baja
    private suspend fun cajaQueCobra(codigo: String): Caja {
        val caja =
            registros.primero(CAJA, Caja::class.java, mapOf("codigo" to codigo))
                ?: throw NotFoundException("No hay ninguna caja con el código '$codigo'")
        if (caja.activa != true) throw ConflictException("La caja $codigo está dada de baja y no puede cobrar")
        return caja
    }
}
