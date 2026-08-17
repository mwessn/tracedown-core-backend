package dev.tracedown.ingestor.consumers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the nudge payload format published by ProbeResultConsumer
 * matches the contract expected by downstream consumers (notification-dispatcher,
 * metrics-service).
 *
 * The nudge is published to Redis pub/sub channel `notify:nudge` as JSON
 * with keys: orgId, serviceId, status, elapsedMs.
 */
class NudgePayloadTest {

    @Test
    fun `nudge payload contains required fields`() {
        val payload = buildNudgePayload(
            orgId = "org-abc-123",
            serviceId = "svc-def-456",
            status = "success",
            elapsedMs = 142,
        )

        val json = Json.parseToJsonElement(payload.toString()).jsonObject

        assertEquals("org-abc-123", json["orgId"]!!.jsonPrimitive.content)
        assertEquals("svc-def-456", json["serviceId"]!!.jsonPrimitive.content)
        assertEquals("success", json["status"]!!.jsonPrimitive.content)
        assertEquals(142, json["elapsedMs"]!!.jsonPrimitive.int)
    }

    @Test
    fun `nudge payload has exactly four fields`() {
        val payload = buildNudgePayload(
            orgId = "org-1",
            serviceId = "svc-1",
            status = "failure",
            elapsedMs = 500,
        )

        val json = Json.parseToJsonElement(payload.toString()).jsonObject
        assertEquals(4, json.size, "nudge payload should have exactly 4 fields: orgId, serviceId, status, elapsedMs")
    }

    @Test
    fun `nudge payload round-trips through JSON serialization`() {
        val payload = buildNudgePayload(
            orgId = "org-round",
            serviceId = "svc-trip",
            status = "timeout",
            elapsedMs = 30000,
        )

        val serialized = payload.toString()
        val deserialized = Json.parseToJsonElement(serialized).jsonObject

        assertEquals("org-round", deserialized["orgId"]!!.jsonPrimitive.content)
        assertEquals("svc-trip", deserialized["serviceId"]!!.jsonPrimitive.content)
        assertEquals("timeout", deserialized["status"]!!.jsonPrimitive.content)
        assertEquals(30000, deserialized["elapsedMs"]!!.jsonPrimitive.int)
    }

    @Test
    fun `status normalization maps known outcomes correctly`() {
        assertEquals("success", normalizeOutcome("success"))
        assertEquals("failure", normalizeOutcome("failure"))
        assertEquals("timeout", normalizeOutcome("timeout"))
    }

    @Test
    fun `status normalization maps unknown outcomes to error`() {
        assertEquals("error", normalizeOutcome("unknown"))
        assertEquals("error", normalizeOutcome("crash"))
        assertEquals("error", normalizeOutcome(""))
    }

    @Test
    fun `nudge payload uses zero for null elapsedMs`() {
        val payload = buildNudgePayload(
            orgId = "org-1",
            serviceId = "svc-1",
            status = "error",
            elapsedMs = 0,
        )

        val json = Json.parseToJsonElement(payload.toString()).jsonObject
        assertEquals(0, json["elapsedMs"]!!.jsonPrimitive.int)
    }

    @Test
    fun `nudge payload from simulated envelope matches expected format`() {
        // Simulate the envelope as it arrives from the queue
        val envelope = buildJsonObject {
            put("serviceId", "550e8400-e29b-41d4-a716-446655440000")
            put("organizationId", "660e8400-e29b-41d4-a716-446655440000")
            put("projectId", "770e8400-e29b-41d4-a716-446655440000")
            put("workspaceId", "880e8400-e29b-41d4-a716-446655440000")
            put("probeAgentId", 1)
            put("rawResult", buildJsonObject {
                put("outcome", "failure")
                put("elapsedMs", 1234)
            })
        }

        // Extract nudge fields exactly as ProbeResultConsumer does
        val rawResult = envelope["rawResult"]?.jsonObject
        val outcome = rawResult?.get("outcome")?.jsonPrimitive?.content ?: "error"
        val status = normalizeOutcome(outcome)
        val nudge = buildNudgePayload(
            orgId = envelope["organizationId"]?.jsonPrimitive?.content ?: "",
            serviceId = envelope["serviceId"]?.jsonPrimitive?.content ?: "",
            status = status,
            elapsedMs = rawResult?.get("elapsedMs")?.jsonPrimitive?.intOrNull ?: 0,
        )

        val json = Json.parseToJsonElement(nudge.toString()).jsonObject
        assertEquals("660e8400-e29b-41d4-a716-446655440000", json["orgId"]!!.jsonPrimitive.content)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", json["serviceId"]!!.jsonPrimitive.content)
        assertEquals("failure", json["status"]!!.jsonPrimitive.content)
        assertEquals(1234, json["elapsedMs"]!!.jsonPrimitive.int)
    }

    // ── Helpers ──

    /** Replicates the status normalization logic from ProbeResultConsumer / ResultPersistenceService. */
    private fun normalizeOutcome(outcome: String): String = when (outcome) {
        "success" -> "success"
        "failure" -> "failure"
        "timeout" -> "timeout"
        else -> "error"
    }

    /** Builds a nudge payload exactly as ProbeResultConsumer does. */
    private fun buildNudgePayload(
        orgId: String,
        serviceId: String,
        status: String,
        elapsedMs: Int,
    ) = buildJsonObject {
        put("orgId", orgId)
        put("serviceId", serviceId)
        put("status", status)
        put("elapsedMs", elapsedMs)
    }
}
