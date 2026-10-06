package caja.comun

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// la marca de una escritura que hace caja por su api (/api/caja/...) o por su buzón: un elemento del contexto de la
// corrutina. Registros lo pone alrededor de cada create y replace, y BuzonStore alrededor de cada marca del publicador
// (como la plataforma); RecordService llama a las guardas en esa misma corrutina, así que GuardiaDeEscrituras lo ve antes
// de escribir. una escritura sin la marca entró por otra puerta (la API genérica de wasichai, /api/objects/..., el admin
// o un módulo), y sobre un objeto de caja no se escribe. un cliente http no puede ponerla: es un objeto del proceso, no
// un dato de la petición
object EscrituraDeCaja : AbstractCoroutineContextElement(Clave) {
    object Clave : CoroutineContext.Key<EscrituraDeCaja>

    override fun toString() = "EscrituraDeCaja"
}
