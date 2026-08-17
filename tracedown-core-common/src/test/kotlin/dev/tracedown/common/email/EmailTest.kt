package dev.tracedown.common.email

import java.io.File
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmailTest {

    private val testDir = File("build/test-email-output")

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    @Test
    fun `file transport writes valid eml with headers`() {
        val emlPath = File(testDir, "test.eml").absolutePath
        val transport = FileTransport("noreply@tracedown.dev", "Tracedown", emlPath)

        transport.send(EmailMessage(
            to = "user@example.com",
            subject = "Welcome",
            htmlBody = "<h1>Hello</h1>",
        ))

        val eml = File(emlPath).readText()
        assertContains(eml, "From: Tracedown <noreply@tracedown.dev>")
        assertContains(eml, "To: user@example.com")
        assertContains(eml, "Subject: Welcome")
        assertContains(eml, "MIME-Version: 1.0")
        assertContains(eml, "Content-Type: text/html; charset=UTF-8")
        assertContains(eml, "Content-Transfer-Encoding: base64")

        val bodyLine = eml.lines().last { it.isNotBlank() }
        val decoded = String(Base64.getMimeDecoder().decode(bodyLine))
        assertEquals("<h1>Hello</h1>", decoded)
    }

    @Test
    fun `file transport writes multipart eml when plaintext provided`() {
        val emlPath = File(testDir, "multipart.eml").absolutePath
        val transport = FileTransport("noreply@tracedown.dev", "Tracedown", emlPath)

        transport.send(EmailMessage(
            to = "user@example.com",
            subject = "Test",
            htmlBody = "<p>HTML body</p>",
            plainTextBody = "Plain body",
            replyTo = "reply@tracedown.dev",
        ))

        val eml = File(emlPath).readText()
        assertContains(eml, "Content-Type: multipart/alternative; boundary=")
        assertContains(eml, "Content-Type: text/plain; charset=UTF-8")
        assertContains(eml, "Content-Type: text/html; charset=UTF-8")
        assertContains(eml, "Reply-To: reply@tracedown.dev")
    }

    @Test
    fun `file transport overwrites on subsequent sends`() {
        val emlPath = File(testDir, "overwrite.eml").absolutePath
        val transport = FileTransport("noreply@tracedown.dev", "Tracedown", emlPath)

        transport.send(EmailMessage(to = "first@example.com", subject = "First", htmlBody = "<p>1</p>"))
        transport.send(EmailMessage(to = "second@example.com", subject = "Second", htmlBody = "<p>2</p>"))

        val eml = File(emlPath).readText()
        assertContains(eml, "To: second@example.com")
        assertContains(eml, "Subject: Second")
        assertTrue("first@example.com" !in eml)
    }

    @Test
    fun `template renderer replaces placeholders`() {
        val html = EmailTemplateRenderer.render("invite", mapOf(
            "inviterName" to "Alice",
            "orgName" to "Acme Corp",
            "inviteLink" to "https://tracedown.app/invite/abc123",
        ))

        assertContains(html, "Alice")
        assertContains(html, "Acme Corp")
        assertContains(html, "https://tracedown.app/invite/abc123")
        assertTrue("{{inviterName}}" !in html)
        assertTrue("{{orgName}}" !in html)
        assertTrue("{{inviteLink}}" !in html)
    }

    @Test
    fun `template renderer throws on missing template`() {
        assertFailsWith<IllegalArgumentException> {
            EmailTemplateRenderer.render("nonexistent", emptyMap())
        }
    }

    @Test
    fun `createTransport selects correct provider`() {
        val config = EmailConfig(
            provider = "console",
            fromAddress = "test@tracedown.dev",
            fromName = "Test",
            smtp = null, resend = null, mailgun = null, file = null,
        )
        val transport = createTransport(config)
        assertTrue(transport is ConsoleTransport)
        transport.close()
    }

    @Test
    fun `createTransport rejects unknown provider`() {
        val config = EmailConfig(
            provider = "pigeon",
            fromAddress = "test@tracedown.dev",
            fromName = "Test",
            smtp = null, resend = null, mailgun = null, file = null,
        )
        assertFailsWith<IllegalArgumentException> {
            createTransport(config)
        }
    }
    // ── ConsoleTransport attachment output ──

    @Test
    fun `console transport writes attachments to disk`() {
        val dir = File(testDir, "console")
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val transport = ConsoleTransport("noreply@t.dev", "Tracedown", dir.path)

        transport.send(
            EmailMessage(
                to = "alice@example.com",
                subject = "Your report",
                htmlBody = "<p>Attached.</p>",
                attachments = listOf(EmailAttachment("uptime-2026-07.pdf", "application/pdf", pdf)),
            )
        )

        val written = dir.listFiles()!!.single()
        assertTrue(written.name.endsWith("-uptime-2026-07.pdf"), "unexpected name ${written.name}")
        // Bytes must land intact — the point is being able to open the document.
        assertContentEquals(pdf, written.readBytes())
    }

    @Test
    fun `console transport keeps a path-bearing filename inside the output dir`() {
        val dir = File(testDir, "console-escape")
        val transport = ConsoleTransport("noreply@t.dev", "Tracedown", dir.path)

        transport.send(
            EmailMessage(
                to = "alice@example.com",
                subject = "Hostile",
                htmlBody = "<p>x</p>",
                attachments = listOf(
                    EmailAttachment("../../escaped.pdf", "application/pdf", byteArrayOf(1)),
                ),
            )
        )

        // The traversal must not write outside the directory.
        val written = dir.listFiles()!!.single()
        assertEquals(dir.canonicalFile, written.canonicalFile.parentFile)
        assertTrue(written.name.endsWith("-escaped.pdf"), "unexpected name ${written.name}")
    }

    @Test
    fun `console transport sends fine with no attachments`() {
        val dir = File(testDir, "console-empty")
        val transport = ConsoleTransport("noreply@t.dev", "Tracedown", dir.path)

        transport.send(
            EmailMessage(to = "a@b.dev", subject = "Plain", htmlBody = "<p>hi</p>")
        )

        // Nothing to write means the directory is never even created.
        assertTrue(dir.listFiles()?.isEmpty() ?: true)
    }
}
