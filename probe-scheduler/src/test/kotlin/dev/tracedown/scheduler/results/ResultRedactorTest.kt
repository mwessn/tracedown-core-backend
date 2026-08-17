package dev.tracedown.scheduler.results

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The redactor must strip every secret plaintext the executor echoed back —
 * wherever it lands (request url, headers, nested calls) — while leaving
 * non-secret content and result shape untouched.
 */
class ResultRedactorTest {

    private val mask = "••••••"

    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `secret in url and header is masked, response body untouched`() {
        val result = parse(
            """
            {
              "outcome": "pass",
              "calls": [{
                "request": {
                  "url": "https://api.example.com/v1?token=SUPERSECRET",
                  "method": "GET",
                  "headers": { "Authorization": "Bearer SUPERSECRET", "Accept": "application/json" }
                },
                "response": { "status": 200, "body": "ok, no secret here" }
              }]
            }
            """.trimIndent(),
        )

        val redacted = ResultRedactor.redact(result, setOf("SUPERSECRET"))
        val out = redacted.toString()

        assertFalse(out.contains("SUPERSECRET"), "secret must not survive anywhere in the result")
        assertTrue(out.contains(mask), "secret occurrences should be masked")
        assertTrue(out.contains("api.example.com"), "non-secret url parts remain")
        assertTrue(out.contains("application/json"), "unrelated headers remain")
        assertTrue(out.contains("ok, no secret here"), "response body is untouched")
    }

    @Test
    fun `multiple secrets and overlapping values all masked`() {
        val result = parse(
            """{ "calls": [{ "request": { "url": "https://h/?a=TOKEN123&b=TOK" } }] }""",
        )
        // "TOK" is a substring of "TOKEN123"; longest-first masking must not leave "EN123" behind.
        val redacted = ResultRedactor.redact(result, setOf("TOK", "TOKEN123"))
        val out = redacted.toString()
        assertFalse(out.contains("TOKEN123"))
        assertFalse(out.contains("EN123"))
    }

    @Test
    fun `no secrets returns the same instance untouched`() {
        val result = parse("""{ "calls": [{ "request": { "url": "https://h/?x=1" } }] }""")
        assertSame(result, ResultRedactor.redact(result, emptySet()))
        assertSame(result, ResultRedactor.redact(result, setOf("", "   ")))
    }

    @Test
    fun `non-string leaves and shape are preserved`() {
        val result = parse("""{ "outcome": "pass", "elapsedMs": 1234, "ok": true, "calls": [] }""")
        val redacted = ResultRedactor.redact(result, setOf("nothing-here"))
        assertEquals(result, redacted)
    }
}
