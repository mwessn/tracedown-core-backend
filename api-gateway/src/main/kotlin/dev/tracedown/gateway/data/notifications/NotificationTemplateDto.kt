package dev.tracedown.gateway.data.notifications

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

@Serializable
data class CreateNotificationTemplateRequest(
    val name: String,
    val text: String,
    val projectIds: List<String>? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 64)?.let(::add)
        Validators.notBlank("text", text)?.let(::add)
        Validators.maxLen("text", text, 10000)?.let(::add)
        Validators.each(projectIds) { Validators.uuid("projectId", it) }?.let(::add)
    }
}

@Serializable
data class UpdateNotificationTemplateRequest(
    val name: String? = null,
    val text: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 64)?.let(::add)
        Validators.maxLen("text", text, 10000)?.let(::add)
    }
}

@Serializable
data class NotificationTemplateSummary(
    val id: String,
    val name: String,
    val text: String,
    val projectIds: List<String>,
    val createdAt: String,
)

@Serializable
data class BindProjectRequest(
    val projectId: String,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("projectId", projectId)?.let(::add)
        Validators.uuid("projectId", projectId)?.let(::add)
    }
}
