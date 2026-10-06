package caja.comun

import org.springframework.stereotype.Component
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService

// los permisos de objeto de core comprobados antes de empezar, diciendo lo que falta (EmisionMasivaService.exigirPermisos
// de srtm). los demás los aplica core al leer o escribir, como el usuario que llama
@Component
class Permisos(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService
) {
    // 403 si falta alguno de los permisos (acción a objeto): «<que> exige permiso de <lo que falta>: <porque>»
    suspend fun exigir(
        usuario: AuthenticatedUser,
        que: String,
        porque: String,
        vararg permisos: Pair<String, String>
    ) {
        val faltan = permisos.filterNot { (accion, objeto) -> puede(usuario, accion, objeto) }.map { (accion, objeto) -> "${nombre(accion)} sobre $objeto" }
        if (faltan.isNotEmpty()) throw ForbiddenException("$que exige permiso de ${faltan.joinToString(" y de ")}: $porque")
    }

    // si el usuario tiene la acción sobre el objeto, con el objectId: sin él solo valen las filas de toda la organización
    suspend fun puede(
        usuario: AuthenticatedUser,
        accion: String,
        objeto: String
    ): Boolean =
        try {
            currentUser.requirePermission(usuario, accion, metadata.loadDefinition(usuario.organizationId, objeto).obj.id)
            true
        } catch (_: ForbiddenException) {
            false
        }

    private fun nombre(accion: String): String =
        when (accion) {
            Actions.READ -> "lectura"
            Actions.CREATE -> "creación"
            Actions.UPDATE -> "edición"
            Actions.DELETE -> "borrado"
            else -> accion
        }
}
