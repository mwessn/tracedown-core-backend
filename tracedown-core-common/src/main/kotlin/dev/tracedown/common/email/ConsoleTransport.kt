package dev.tracedown.common.email

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Development transport: prints the message instead of sending it.
 *
 * Attachment bytes are meaningless in a log, so they are written to
 * [attachmentDir] and the path is printed alongside — that way a generated
 * document can actually be opened while developing. The default lives under
 * `build/`, which git already ignores; anything else configured must be ignored
 * too, since these files are real correspondence.
 */
class ConsoleTransport(
    private val fromAddress: String,
    private val fromName: String,
    private val attachmentDir: String = DEFAULT_ATTACHMENT_DIR,
    /**
     * Whether to print full message bodies to the log. Bodies routinely carry
     * secrets — password-reset links, invite tokens — so this defaults to `false`
     * (bodies omitted, only their length noted). Enable it only in a local dev
     * session where seeing the rendered body in the console is the point.
     */
    private val logBodies: Boolean = false,
) : EmailTransport {

    private val log = LoggerFactory.getLogger(ConsoleTransport::class.java)

    override fun send(message: EmailMessage) {
        val written = writeAttachments(message)
        val html = if (logBodies) message.htmlBody else "(omitted, ${message.htmlBody.length} chars)"
        val text = if (logBodies) {
            message.plainTextBody ?: "(none)"
        } else {
            message.plainTextBody?.let { "(omitted, ${it.length} chars)" } ?: "(none)"
        }
        log.info(
            """
            |========== EMAIL ==========
            |From: $fromName <$fromAddress>
            |To: ${message.to}
            |Subject: ${message.subject}
            |Reply-To: ${message.replyTo ?: "(none)"}
            |---------- HTML -----------
            |$html
            |---------- TEXT -----------
            |$text
            |------- ATTACHMENTS -------
            |${if (written.isEmpty()) "(none)" else written.joinToString("\n")}
            |===========================
            """.trimMargin()
        )
    }

    override fun sendBatch(messages: List<EmailMessage>) {
        log.info("Sending batch of {} emails to console", messages.size)
        for (message in messages) {
            send(message)
        }
    }

    override fun close() {}

    /**
     * Writes each attachment to [attachmentDir], returning a description line
     * per attachment for the console block.
     *
     * Names are prefixed with a UTC timestamp so repeated sends of the same
     * document (a resend, a retried job) do not overwrite each other.
     * A write failure is reported but never propagates: this is a development
     * aid, and it must not turn into a delivery failure.
     */
    private fun writeAttachments(message: EmailMessage): List<String> =
        message.attachments.map { attachment ->
            val size = "${attachment.contentType}, ${attachment.content.size} bytes"
            try {
                val dir = File(attachmentDir)
                dir.mkdirs()
                val stamp = TIMESTAMP.format(Instant.now())
                val file = File(dir, "$stamp-${attachment.filename.sanitized()}")
                file.writeBytes(attachment.content)
                "${attachment.filename} ($size) -> ${file.absolutePath}"
            } catch (e: Exception) {
                log.error("Could not write attachment {}: {}", attachment.filename, e.message)
                "${attachment.filename} ($size) -> (write failed: ${e.message})"
            }
        }

    /** Keeps a hostile or path-bearing filename from escaping [attachmentDir]. */
    private fun String.sanitized(): String =
        substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        /** Under `build/`, so generated correspondence is git-ignored by default. */
        const val DEFAULT_ATTACHMENT_DIR = "build/email-attachments"

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
    }
}
