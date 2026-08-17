package dev.tracedown.common.email

/**
 * An [EmailTransport] that hands a message to the email-service queue instead of
 * delivering it itself.
 *
 * Only email-service owns real transports (SMTP, Mailgun, Resend, …); every
 * other service publishes to `email_queue` and lets it deliver, so provider
 * configuration, the shared layout, delivery logging and status events all live
 * in one place. This adapter exists so a service written against the
 * [EmailTransport] seam gets that hand-off without changing its call sites.
 *
 * Bodies are published pre-baked ([EmailPublisher.publishBody]) and wrapped in
 * the global layout by email-service; the plain-text alternative is dropped,
 * since the queue's body mode carries HTML only.
 */
class QueuedEmailTransport(
    private val publisher: EmailPublisher,
    /** Recorded on each job for delivery-log attribution, e.g. "api-gateway". */
    private val source: String,
) : EmailTransport {

    override fun send(message: EmailMessage) {
        publisher.publishBody(
            to = message.to,
            subject = message.subject,
            body = message.htmlBody,
            source = source,
            replyTo = message.replyTo,
            attachments = message.attachments,
        )
    }

    override fun sendBatch(messages: List<EmailMessage>) {
        messages.forEach(::send)
    }

    /** The Redis connection belongs to whoever built the publisher. */
    override fun close() {}
}
