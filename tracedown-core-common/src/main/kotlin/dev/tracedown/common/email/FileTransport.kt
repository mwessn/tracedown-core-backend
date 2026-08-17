package dev.tracedown.common.email

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

class FileTransport(
    private val fromAddress: String,
    private val fromName: String,
    private val outputPath: String,
) : EmailTransport {

    private val log = LoggerFactory.getLogger(FileTransport::class.java)

    private val dateFormat = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        .withZone(ZoneOffset.UTC)

    override fun send(message: EmailMessage) {
        val file = File(outputPath)
        file.parentFile?.mkdirs()

        val altBoundary = "----=_Alt_${UUID.randomUUID()}"
        val mixedBoundary = "----=_Mixed_${UUID.randomUUID()}"
        val now = dateFormat.format(Instant.now())
        val hasAttachments = message.attachments.isNotEmpty()

        val eml = buildString {
            appendLine("From: $fromName <$fromAddress>")
            appendLine("To: ${message.to}")
            appendLine("Subject: ${message.subject}")
            appendLine("Date: $now")
            appendLine("MIME-Version: 1.0")
            message.replyTo?.let { appendLine("Reply-To: $it") }
            appendLine("Message-ID: <${UUID.randomUUID()}@${fromAddress.substringAfter("@")}>")

            // With attachments the body becomes one part of a multipart/mixed
            // envelope; the text/html alternative nests inside it.
            if (hasAttachments) {
                appendLine("Content-Type: multipart/mixed; boundary=\"$mixedBoundary\"")
                appendLine()
                appendLine("--$mixedBoundary")
            }

            appendBody(message, altBoundary)

            for (attachment in message.attachments) {
                appendLine()
                appendLine("--$mixedBoundary")
                appendLine("Content-Type: ${attachment.contentType}; name=\"${attachment.filename}\"")
                appendLine("Content-Transfer-Encoding: base64")
                appendLine("Content-Disposition: attachment; filename=\"${attachment.filename}\"")
                appendLine()
                appendLine(encode(attachment.content))
            }
            if (hasAttachments) {
                appendLine()
                appendLine("--$mixedBoundary--")
            }
        }

        file.writeText(eml)
        log.debug("Email written to {}", file.absolutePath)
    }

    /** The message body: a text/html + text/plain alternative, or bare HTML. */
    private fun StringBuilder.appendBody(message: EmailMessage, boundary: String) {
        if (message.plainTextBody != null) {
            appendLine("Content-Type: multipart/alternative; boundary=\"$boundary\"")
            appendLine()
            appendLine("--$boundary")
            appendLine("Content-Type: text/plain; charset=UTF-8")
            appendLine("Content-Transfer-Encoding: base64")
            appendLine()
            appendLine(encode(message.plainTextBody.toByteArray()))
            appendLine()
            appendLine("--$boundary")
            appendLine("Content-Type: text/html; charset=UTF-8")
            appendLine("Content-Transfer-Encoding: base64")
            appendLine()
            appendLine(encode(message.htmlBody.toByteArray()))
            appendLine()
            appendLine("--$boundary--")
        } else {
            appendLine("Content-Type: text/html; charset=UTF-8")
            appendLine("Content-Transfer-Encoding: base64")
            appendLine()
            appendLine(encode(message.htmlBody.toByteArray()))
        }
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(bytes)

    override fun sendBatch(messages: List<EmailMessage>) {
        log.info("Writing batch of {} emails to {} (only last email retained)", messages.size, outputPath)
        for (message in messages) {
            send(message)
        }
    }

    override fun close() {}
}
