package caja.comun

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// la marca de una escritura que hace caja por su api (/api/caja/...): un elemento del contexto de la corrutina. Registros
// lo pone alrededor de cada create, replace y delete, y RecordService llama a sus RecordChangeListener en esa misma
// corrutina, así que GuardiaDeEscrituras lo ve. una escritura sin la marca entró por otra puerta: la API genérica de
// wasichai (/api/objects/...), el admin o un módulo
object EscrituraDeCaja : AbstractCoroutineContextElement(Clave) {
    object Clave : CoroutineContext.Key<EscrituraDeCaja>

    override fun toString() = "EscrituraDeCaja"
}
