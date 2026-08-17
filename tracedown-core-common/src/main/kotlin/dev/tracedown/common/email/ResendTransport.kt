package dev.tracedown.common.email

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.TimeUnit

class ResendTransport(
    private val fromAddress: String,
    private val fromName: String,
    private val config: ResendConfig,
) : EmailTransport {

    private val log = LoggerFactory.getLogger(ResendTransport::class.java)
    private val jsonType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun send(message: EmailMessage) {
        val body = buildJson(message)
        val request = Request.Builder()
            .url("https://api.resend.com/emails")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                throw RuntimeException("Resend API error ${response.code}: $responseBody")
            }
        }
    }

    override fun sendBatch(messages: List<EmailMessage>) {
        log.info("Sending batch of {} emails via Resend", messages.size)
        for (message in messages) {
            send(message)
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun buildJson(message: EmailMessage): String {
        val from = "$fromName <$fromAddress>"
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"from\":\"${escapeJson(from)}\",")
        sb.append("\"to\":[\"${escapeJson(message.to)}\"],")
        sb.append("\"subject\":\"${escapeJson(message.subject)}\",")
        sb.append("\"html\":\"${escapeJson(message.htmlBody)}\"")
        message.plainTextBody?.let {
            sb.append(",\"text\":\"${escapeJson(it)}\"")
        }
        message.replyTo?.let {
            sb.append(",\"reply_to\":\"${escapeJson(it)}\"")
        }
        if (message.attachments.isNotEmpty()) {
            // Resend takes attachment bytes base64-encoded inline.
            sb.append(",\"attachments\":[")
            sb.append(
                message.attachments.joinToString(",") { attachment ->
                    val encoded = Base64.getEncoder().encodeToString(attachment.content)
                    """{"filename":"${escapeJson(attachment.filename)}","content":"$encoded"}"""
                }
            )
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }
}

internal fun escapeJson(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
