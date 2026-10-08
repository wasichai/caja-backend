package caja.comun

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore

// el cuerpo de una petición a la api de caja, en lista blanca: una clave que no es una propiedad del cuerpo no se
// ignora, se anota en desconocidos, y las reglas de la petición la rechazan con un 400 que la nombra
// (sinCamposDesconocidos). callarla dejaría creer al cliente que se guardó: un tributo en el alta de una orden, un precio
// en un cobro de tasas. todo @RequestBody de caja hereda de aquí (CuerpoEstrictoTest lo vigila)
abstract class CuerpoEstricto {
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
