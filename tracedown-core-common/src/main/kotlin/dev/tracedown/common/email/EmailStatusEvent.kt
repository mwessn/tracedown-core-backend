package dev.tracedown.common.email

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Delivery-status feedback for a notification email.
 *
 * Published by the email-service to [QUEUE_KEY] after a send attempt when the
 * email job envelope carries a `notificationLogId`. Consumed by the
 * notification-dispatcher, which transitions the matching notification_log
 * row from `queued` to the actual outcome (`sent` / `failed`).
 */
data class EmailStatusEvent(
    val notificationLogId: UUID,
    val status: String,
    val error: String? = null,
) {
    companion object {
        const val QUEUE_KEY = "email_status_queue"

        /** Parses an event from a queue envelope, or returns null if malformed. */
        fun parse(envelope: JsonObject): EmailStatusEvent? {
            val rawId = envelope["notificationLogId"]?.jsonPrimitive?.contentOrNull ?: return null
            val status = envelope["status"]?.jsonPrimitive?.contentOrNull ?: return null
            val id = try {
                UUID.fromString(rawId)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return EmailStatusEvent(id, status, envelope["error"]?.jsonPrimitive?.contentOrNull)
        }
    }

    fun toEnvelope(): JsonObject = buildJsonObject {
        put("notificationLogId", notificationLogId.toString())
        put("status", status)
        if (error != null) put("error", error)
    }
}
