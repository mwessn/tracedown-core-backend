package dev.tracedown.common.email

interface EmailTransport {

    fun send(message: EmailMessage)

    fun sendBatch(messages: List<EmailMessage>)

    fun close()
}
