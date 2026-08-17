package dev.tracedown.scheduler.results

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Redacts secret variable values out of a raw ProbeResult before it leaves the
 * scheduler for the results queue.
 *
 * A script may embed a `secret` variable in a request URL or header; the executor
 * echoes the resolved request (url + headers) back in the ProbeResult, so the
 * plaintext would otherwise be persisted verbatim and shown to org members. This
 * walks every string leaf of the result and replaces any occurrence of a secret's
 * plaintext with a fixed mask — covering `request.url`, `request.headers.*`,
 * redirect chains, and any other string field, wherever the value happens to land.
 *
 * Only SECRET variables are redacted (never `variable`/`metric`), and only their
 * resolved plaintext values for this run (surfaced by [VariableResolver]) — so
 * unrelated response content is untouched unless it literally equals a secret.
 */
object ResultRedactor {

    private const val MASK = "••••••" // ••••••

    /** Returns [result] with every occurrence of a secret plaintext masked. */
    fun redact(result: JsonObject, secretValues: Set<String>): JsonObject {
        if (secretValues.isEmpty()) return result
        // Longest-first so that a secret that is a substring of another is masked
        // fully rather than leaving a fragment behind.
        val secrets = secretValues.filter { it.isNotBlank() }.sortedByDescending { it.length }
        if (secrets.isEmpty()) return result
        return redactElement(result, secrets) as JsonObject
    }

    private fun redactElement(el: JsonElement, secrets: List<String>): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { redactElement(it.value, secrets) })
        is JsonArray -> JsonArray(el.map { redactElement(it, secrets) })
        is JsonPrimitive -> if (el.isString) redactString(el.content, secrets) else el
    }

    private fun redactString(value: String, secrets: List<String>): JsonPrimitive {
        var out = value
        for (secret in secrets) {
            if (out.contains(secret)) out = out.replace(secret, MASK)
        }
        return if (out == value) JsonPrimitive(value) else JsonPrimitive(out)
    }
}
