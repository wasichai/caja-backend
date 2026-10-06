package caja.buzon

import org.springframework.stereotype.Component
import wasichai.core.platform.ClusterLock

// UN SOLO PUBLICADOR POR BASE, no uno por réplica. en cada vuelta el publicador intenta el candado de clúster del buzón,
// ClusterLock.tryLock de wasichai: un pg_try_advisory_lock de SESIÓN sobre una conexión propia, abierta por debajo del
// pool, que retiene mientras lo tiene. si otra réplica lo tiene, esa vuelta no hace nada. una instancia que muere no se
// lo queda: postgres lo suelta al cerrarse su sesión. al soltarlo (Lease.release, también si la vuelta se cancela) se
// libera el candado y se cierra la conexión: nunca vuelve a un pool con el candado puesto. no es un candado de
// transacción (Candados): ese vive en una transacción, y la vuelta no abre ninguna mientras habla con el destino
@Component
class CerrojoBuzon(
    private val cerrojos: ClusterLock
) {
    // null: otro publicador lo tiene
    suspend fun tomar(): ClusterLock.Lease? = cerrojos.tryLock(CLAVE)

    private companion object {
        // la clave del publicador entre los candados de clúster de la base (ClusterLock.lockId la vuelve un bigint)
        const val CLAVE = "caja.buzon"
    }
}
