package dev.tracedown.email.processing

import dev.tracedown.common.email.EmailMessage
import dev.tracedown.common.email.EmailTransport
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class EmailProcessorTest {

    private lateinit var sentEmails: CopyOnWriteArrayList<EmailMessage>
    private lateinit var transport: EmailTransport
    private lateinit var processor: EmailProcessor

    @BeforeEach
    fun setUp() {
        sentEmails = CopyOnWriteArrayList()
        transport = object : EmailTransport {
            override fun send(message: EmailMessage) { sentEmails.add(message) }
            override fun sendBatch(messages: List<EmailMessage>) { sentEmails.addAll(messages) }
            override fun close() {}
        }
        // Use a processor without Redis (skip idempotency in unit tests)
        processor = TestableEmailProcessor(transport)
    }

    @Test
    fun `processes named template email`() {
        val envelope = buildJsonObject {
            put("id", "test-1")
            put("to", "alice@example.com")
            put("subject", "You've been invited")
            put("type", "system.invite")
            put("vars", buildJsonObject {
                put("inviterName", "Bob")
                put("orgName", "Acme")
                put("inviteLink", "https://app.tracedown.dev/invite/abc123")
            })
            put("source", "api-gateway")
            put("createdAt", "2026-05-05T12:00:00Z")
        }

        processor.process(envelope)

        assertEquals(1, sentEmails.size)
        val msg = sentEmails[0]
        assertEquals("alice@example.com", msg.to)
        assertEquals("You've been invited", msg.subject)
        assertTrue(msg.htmlBody.contains("Bob"))
        assertTrue(msg.htmlBody.contains("Acme"))
        assertTrue(msg.htmlBody.contains("https://app.tracedown.dev/invite/abc123"))
    }

    @Test
    fun `processes body-mode email with layout`() {
        val envelope = buildJsonObject {
            put("id", "test-2")
            put("to", "charlie@example.com")
            put("subject", "[Tracedown] API Monitor — expect failure")
            put("body", "<span class=\"var\">API Monitor</span> failed: expected 200, got 503")
            put("source", "notification-dispatcher")
            put("createdAt", "2026-05-05T12:00:00Z")
        }

        processor.process(envelope)

        assertEquals(1, sentEmails.size)
        val msg = sentEmails[0]
        assertEquals("charlie@example.com", msg.to)
        assertTrue(msg.htmlBody.contains("API Monitor"))
        assertTrue(msg.htmlBody.contains("expected 200, got 503"))
        // Should be wrapped in layout (contains Tracedown header)
        assertTrue(msg.htmlBody.contains("Tracedown"))
    }

    @Test
    fun `skips email with no type or body`() {
        val envelope = buildJsonObject {
            put("id", "test-3")
            put("to", "alice@example.com")
            put("subject", "Incomplete")
            put("source", "test")
            put("createdAt", "2026-05-05T12:00:00Z")
        }

        processor.process(envelope)

        assertEquals(0, sentEmails.size)
    }

    @Test
    fun `skips email with unknown template type`() {
        val envelope = buildJsonObject {
            put("id", "test-4")
            put("to", "alice@example.com")
            put("subject", "Unknown template")
            put("type", "nonexistent.template")
            put("vars", buildJsonObject {})
            put("source", "test")
            put("createdAt", "2026-05-05T12:00:00Z")
        }

        processor.process(envelope)

        assertEquals(0, sentEmails.size)
    }

    @Test
    fun `deduplicates by message id`() {
        val deduplicatingProcessor = TestableEmailProcessor(transport, dedup = true)

        val envelope = buildJsonObject {
            put("id", "dedup-1")
            put("to", "alice@example.com")
            put("subject", "Test")
            put("body", "Hello")
            put("source", "test")
            put("createdAt", "2026-05-05T12:00:00Z")
        }

        deduplicatingProcessor.process(envelope)
        deduplicatingProcessor.process(envelope)

        assertEquals(1, sentEmails.size, "Second call should be deduplicated")
    }

    @Test
    fun `template renders variables correctly`() {
        val envelope = buildJsonObject {
            put("id", "test-5")
            put("to", "user@example.com")
            put("subject", "Reset your password")
            put("type", "system.password-reset")
            put("vars", buildJsonObject {
                put("userName", "TestUser")
                put("expiryMinutes", "60")
                put("resetLink", "https://app.tracedown.dev/reset/token123")
            })
            put("source", "api-gateway")
            put("createdAt", "2026-05-05T12:00:00Z")
        }

        processor.process(envelope)

        assertEquals(1, sentEmails.size)
        val html = sentEmails[0].htmlBody
        assertTrue(html.contains("TestUser"))
        assertTrue(html.contains("60"))
        assertTrue(html.contains("https://app.tracedown.dev/reset/token123"))
        assertFalse(html.contains("{{userName}}"), "Variables should be interpolated")
    }
    @Test
    fun `decodes base64 attachments off the envelope`() {
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37)
        val envelope = buildJsonObject {
            put("id", "test-attach")
            put("to", "alice@example.com")
            put("subject", "Your report")
            put("body", "<p>Attached.</p>")
            put("source", "api-gateway")
            put("attachments", buildJsonArray {
                add(buildJsonObject {
                    put("filename", "uptime-2026-07.pdf")
                    put("contentType", "application/pdf")
                    put("content", Base64.getEncoder().encodeToString(pdf))
                })
            })
            put("createdAt", "2026-07-23T12:00:00Z")
        }

        processor.process(envelope)

        val msg = sentEmails.single()
        val attachment = msg.attachments.single()
        assertEquals("uptime-2026-07.pdf", attachment.filename)
        assertEquals("application/pdf", attachment.contentType)
        // The bytes must survive the round trip intact, or the PDF is corrupt.
        assertArrayEquals(pdf, attachment.content)
    }

    @Test
    fun `a malformed attachment is dropped but the email still sends`() {
        val envelope = buildJsonObject {
            put("id", "test-bad-attach")
            put("to", "alice@example.com")
            put("subject", "Your report")
            put("body", "<p>Attached.</p>")
            put("source", "api-gateway")
            put("attachments", buildJsonArray {
                add(buildJsonObject {
                    put("filename", "broken.pdf")
                    put("contentType", "application/pdf")
                    put("content", "not-valid-base64!!!")
                })
            })
            put("createdAt", "2026-07-23T12:00:00Z")
        }

        processor.process(envelope)

        // Delivery matters more than the attachment: the body still goes out.
        val msg = sentEmails.single()
        assertTrue(msg.attachments.isEmpty())
    }

    @Test
    fun `no attachments key yields an empty list`() {
        val envelope = buildJsonObject {
            put("id", "test-no-attach")
            put("to", "alice@example.com")
            put("subject", "Plain")
            put("body", "<p>Hi.</p>")
            put("source", "api-gateway")
            put("createdAt", "2026-07-23T12:00:00Z")
        }

        processor.process(envelope)

        assertTrue(sentEmails.single().attachments.isEmpty())
    }

    @Test
    fun `body without a footer drops the footer row entirely`() {
        val envelope = buildJsonObject {
            put("id", "test-no-footer")
            put("to", "alice@example.com")
            put("subject", "Your report")
            put("body", "<p>Your report is ready.</p>")
            put("source", "api-gateway")
            put("createdAt", "2026-07-23T12:00:00Z")
        }

        processor.process(envelope)

        val html = sentEmails.single().htmlBody
        assertTrue(html.contains("Your report is ready."))
        // Directly-requested mail must not claim the recipient subscribed to
        // anything, nor be left with an empty bordered strip.
        assertFalse(html.contains("You received this because"))
        assertFalse(html.contains("{{footer}}"))
        assertFalse(html.contains("FOOTER_START"))
    }

    @Test
    fun `body with a footer renders it`() {
        val envelope = buildJsonObject {
            put("id", "test-footer")
            put("to", "alice@example.com")
            put("subject", "Service down")
            put("body", "<p>api.example.com is failing.</p>")
            put("source", "notification-dispatcher")
            put("footer", "You received this because you have access to a monitored service on Tracedown.")
            put("createdAt", "2026-07-23T12:00:00Z")
        }

        processor.process(envelope)

        val html = sentEmails.single().htmlBody
        assertTrue(html.contains("You received this because you have access to a monitored service"))
        assertFalse(html.contains("{{footer}}"))
    }
}

/****
 * Testable EmailProcessor that skips Redis idempotency.
 */
private class TestableEmailProcessor(
    private val emailTransport: EmailTransport,
    private val dedup: Boolean = false,
) : EmailProcessor(emailTransport, null) {

    private val sentIds = mutableSetOf<String>()

    override fun checkIdempotency(id: String): Boolean {
        if (!dedup) return true
        return sentIds.add(id)
    }
}
