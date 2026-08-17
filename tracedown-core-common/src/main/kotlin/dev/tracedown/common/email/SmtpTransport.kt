package dev.tracedown.common.email

import org.simplejavamail.api.mailer.Mailer
import org.simplejavamail.api.mailer.config.TransportStrategy
import org.simplejavamail.email.EmailBuilder
import org.simplejavamail.mailer.MailerBuilder
import org.slf4j.LoggerFactory

class SmtpTransport(
    private val fromAddress: String,
    private val fromName: String,
    private val config: SmtpConfig,
) : EmailTransport {

    private val log = LoggerFactory.getLogger(SmtpTransport::class.java)

    private val mailer: Mailer = MailerBuilder
        .withSMTPServer(config.host, config.port, config.username, config.password)
        .withTransportStrategy(
            when (config.tlsMode) {
                TlsMode.STARTTLS -> TransportStrategy.SMTP_TLS
                TlsMode.SMTPS -> TransportStrategy.SMTPS
                TlsMode.PLAIN -> TransportStrategy.SMTP
            }
        )
        .withProperty("mail.smtp.connectiontimeout", config.connectionTimeoutMs.toString())
        .withProperty("mail.smtp.timeout", config.readTimeoutMs.toString())
        .async()
        .buildMailer()

    override fun send(message: EmailMessage) {
        mailer.sendMail(buildEmail(message))
    }

    override fun sendBatch(messages: List<EmailMessage>) {
        log.info("Sending batch of {} emails via SMTP", messages.size)
        for (message in messages) {
            mailer.sendMail(buildEmail(message), true)
        }
    }

    override fun close() {
        mailer.close()
    }

    private fun buildEmail(message: EmailMessage): org.simplejavamail.api.email.Email {
        return EmailBuilder.startingBlank()
            .from(fromName, fromAddress)
            .to(message.to)
            .withSubject(message.subject)
            .withHTMLText(message.htmlBody)
            .apply {
                message.plainTextBody?.let { withPlainText(it) }
                message.replyTo?.let { withReplyTo(it) }
                for (attachment in message.attachments) {
                    withAttachment(attachment.filename, attachment.content, attachment.contentType)
                }
            }
            .buildEmail()
    }
}
