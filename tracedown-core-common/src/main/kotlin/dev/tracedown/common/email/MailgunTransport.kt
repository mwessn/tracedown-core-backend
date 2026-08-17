package dev.tracedown.common.email

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class MailgunTransport(
    private val fromAddress: String,
    private val fromName: String,
    private val config: MailgunConfig,
) : EmailTransport {

    private val log = LoggerFactory.getLogger(MailgunTransport::class.java)

    private val baseUrl = when (config.region) {
        "eu" -> "https://api.eu.mailgun.net/v3"
        else -> "https://api.mailgun.net/v3"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun send(message: EmailMessage) {
        // Mailgun takes attachments only as multipart/form-data, so a message
        // carrying files uses that encoding instead of the simple form body.
        val formBody = if (message.attachments.isEmpty()) {
            FormBody.Builder()
                .add("from", "$fromName <$fromAddress>")
                .add("to", message.to)
                .add("subject", message.subject)
                .add("html", message.htmlBody)
                .apply {
                    message.plainTextBody?.let { add("text", it) }
                    message.replyTo?.let { add("h:Reply-To", it) }
                }
                .build()
        } else {
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("from", "$fromName <$fromAddress>")
                .addFormDataPart("to", message.to)
                .addFormDataPart("subject", message.subject)
                .addFormDataPart("html", message.htmlBody)
                .apply {
                    message.plainTextBody?.let { addFormDataPart("text", it) }
                    message.replyTo?.let { addFormDataPart("h:Reply-To", it) }
                    for (attachment in message.attachments) {
                        addFormDataPart(
                            "attachment",
                            attachment.filename,
                            attachment.content.toRequestBody(attachment.contentType.toMediaType()),
                        )
                    }
                }
                .build()
        }

        val request = Request.Builder()
            .url("$baseUrl/${config.domain}/messages")
            .header("Authorization", Credentials.basic("api", config.apiKey))
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                throw RuntimeException("Mailgun API error ${response.code}: $responseBody")
            }
        }
    }

    override fun sendBatch(messages: List<EmailMessage>) {
        log.info("Sending batch of {} emails via Mailgun", messages.size)
        for (message in messages) {
            send(message)
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
