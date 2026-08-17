package dev.tracedown.gateway.routes.v1.me

import dev.tracedown.gateway.controllers.me.UserDataController
import dev.tracedown.gateway.data.me.ChangeEmailRequest
import dev.tracedown.gateway.routes.v1.auth.requireAuth
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * @OpenAPITag Me
 * Account-scoped data: personal data export and email change.
 */
@Resource("/api/v1/me")
class Me {
    @Resource("export")
    class Export(val parent: Me = Me())

    @Resource("email")
    class Email(val parent: Me = Me())
}

fun Route.meRoutes() {
    /**
     * Returns a single JSON document of all data stored about the calling
     * user (secrets excluded). Versioned envelope — see UserDataExport.
     */
    get<Me.Export> {
        val principal = requireAuth(call)
        call.respond(UserDataController.export(principal.userId, principal.sessionId))
    }

    /**
     * Changes the account email. Requires the current password, plus a TOTP
     * code when enrolled. Revokes all other sessions and returns the updated
     * profile.
     */
    post<Me.Email> {
        val principal = requireAuth(call)
        val body = tryReceive<ChangeEmailRequest>(call)
        val result = UserDataController.changeEmail(
            userId = principal.userId,
            sessionId = principal.sessionId,
            orgId = principal.organizationId,
            request = body,
        )
        call.respond(result)
    }
}
