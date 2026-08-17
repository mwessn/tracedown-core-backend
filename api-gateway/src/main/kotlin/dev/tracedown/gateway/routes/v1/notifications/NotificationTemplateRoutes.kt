package dev.tracedown.gateway.routes.v1.notifications

import dev.tracedown.gateway.controllers.notifications.NotificationTemplateController
import dev.tracedown.gateway.data.notifications.BindProjectRequest
import dev.tracedown.gateway.data.notifications.CreateNotificationTemplateRequest
import dev.tracedown.gateway.data.notifications.UpdateNotificationTemplateRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post

/**
 * @OpenAPITag Notification Templates
 * Notification template management: CRUD, project binding/unbinding.
 */
@Resource("/api/v1/notification-templates")
class NotificationTemplates {
    @Resource("{id}")
    class ById(val parent: NotificationTemplates = NotificationTemplates(), val id: String) {
        @Resource("projects")
        class Projects(val parent: ById) {
            @Resource("{projectId}")
            class ByProjectId(val parent: Projects, val projectId: String)
        }
    }
}

fun Route.notificationTemplateRoutes() {
    /** Creates a new notification template. */
    post<NotificationTemplates> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateNotificationTemplateRequest>(call)
        val result = NotificationTemplateController.create(orgId, body, principal.userId)
        call.respond(HttpStatusCode.Created, result)
    }

    /** Lists all notification templates for the organization. */
    get<NotificationTemplates> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        val result = NotificationTemplateController.list(orgId, principal.userId, pfs)
        call.respond(result)
    }

    /** Returns a single notification template. */
    get<NotificationTemplates.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val templateId = parseUuid(resource.id, "id")
        val result = NotificationTemplateController.get(orgId, templateId, principal.userId)
        call.respond(result)
    }

    /** Updates a notification template. */
    patch<NotificationTemplates.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val templateId = parseUuid(resource.id, "id")
        val body = tryReceive<UpdateNotificationTemplateRequest>(call)
        val result = NotificationTemplateController.update(orgId, templateId, body, principal.userId)
        call.respond(result)
    }

    /** Soft-deletes a notification template. */
    delete<NotificationTemplates.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val templateId = parseUuid(resource.id, "id")
        NotificationTemplateController.delete(orgId, templateId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Binds the template to a project. */
    post<NotificationTemplates.ById.Projects> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val templateId = parseUuid(resource.parent.id, "id")
        val body = tryReceive<BindProjectRequest>(call)
        val result = NotificationTemplateController.bindProject(orgId, templateId, body, principal.userId)
        call.respond(result)
    }

    /** Unbinds the template from a project. */
    delete<NotificationTemplates.ById.Projects.ByProjectId> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val templateId = parseUuid(resource.parent.parent.id, "id")
        val projectId = parseUuid(resource.projectId, "projectId")
        val result = NotificationTemplateController.unbindProject(orgId, templateId, projectId, principal.userId)
        call.respond(result)
    }
}
