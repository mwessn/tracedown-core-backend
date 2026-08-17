package dev.tracedown.common.email

/**
 * A file sent alongside an email.
 *
 * Attachments travel through the Redis queue base64-encoded, so they inflate the
 * job by roughly a third — keep them to small documents (a report, an export),
 * never bulk payloads.
 */
data class EmailAttachment(
    val filename: String,
    /** MIME type, e.g. `application/pdf`. */
    val contentType: String,
    val content: ByteArray,
) {
    // A data class compares arrays by identity, which would make two attachments
    // holding the same bytes unequal — override so equality follows content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmailAttachment) return false
        return filename == other.filename &&
            contentType == other.contentType &&
            content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

data class EmailMessage(
    val to: String,
    val subject: String,
    val htmlBody: String,
    val plainTextBody: String? = null,
    val replyTo: String? = null,
    val attachments: List<EmailAttachment> = emptyList(),
)
