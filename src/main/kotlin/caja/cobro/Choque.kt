package caja.cobro

import caja.modelo.OrdenDeCobro
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

// el alta que choca (caja-backend#40): la misma referencia que vuelve con otra deuda. el reenvío del alta es
// idempotente solo si pide lo mismo que ya está; si pide otra cosa, devolver la vieja con 200 haría creer al origen que
// caja cobrará lo que acaba de mandar. se compara lo que es la deuda: cuánto, desde cuándo y a quién. no se compara
// actualizado_a (srtm no tiene intereses: la cifra no cambia con la fecha a la que se dice), ni el concepto, el detalle o
// el nombre, que son lo que se imprime y no cambian lo que se cobra

// los campos de la deuda que difieren, en este orden, por su clave snake_case. pedida es el alta como la normalizan las
// reglas (el importe en cadena, la fecha ISO, el documento en mayúsculas). sustituye_a cuenta solo si la pedida lo trae:
// un reenvío que nombra otra vieja, o una nueva que ya estaba sin sustituir a nadie, no son el mismo pedido
fun diferencias(
    existente: OrdenDeCobro,
    pedida: Map<String, Any?>
): List<String> =
    buildList {
        // compareTo y no equals: 100.0 y 100.00 son la misma deuda
        val importe = (pedida["importe"] as String?)?.let(::BigDecimal)
        if (importe == null || existente.importe == null || importe.compareTo(existente.importe) != 0) add("importe")
        if ((pedida["fecha_exigibilidad"] as String?)?.let(LocalDate::parse) != existente.fechaExigibilidad) add("fecha_exigibilidad")
        if (documento(pedida["pagador_documento"] as String?) != documento(existente.pagadorDocumento)) add("pagador_documento")
        if ("sustituye_a" in pedida && pedida["sustituye_a"] != existente.sustituyeA) add("sustituye_a")
    }

private fun documento(valor: String?): String? = valor?.uppercase(Locale.ROOT)
