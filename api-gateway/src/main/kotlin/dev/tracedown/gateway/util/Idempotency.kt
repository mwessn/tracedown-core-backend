package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.PipelineRequest
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * `Idempotency-Key` on the key-authenticated API's POSTs: a request that may
 * be sent twice — a retry after a timeout, a pipeline step run again — and
 * must take effect once.
 *
 * The first request with a key is remembered, per API key, for [TTL_SECONDS]:
 * what was asked (the method, the path with its query, and a hash of the body
 * — the *fingerprint*) and, once it is answered, the answer (status, type,
 * body). After that, the same key:
 *
 *  - with the same fingerprint is answered with the remembered answer, and
 *    `Idempotent-Replayed: true`, without the handler running again;
 *  - with a different fingerprint is 422 `idempotency_key_reused`: the key
 *    names another request, and guessing which was meant is not this layer's
 *    to do;
 *  - while the first is still being answered, 409 `idempotency_in_progress`.
 *
 * A request answered with a server error (5xx), or whose answer could not be
 * kept (a streamed one), is not remembered: the next one with its key runs.
 * A request that never finished — the gateway went away mid-call — holds its
 * key for [IN_FLIGHT_TTL_SECONDS] at most.
 *
 * Kept in Redis A, which every gateway replica shares. When it does not
 * answer, a request carrying a key is refused, 503 `idempotency_unavailable`,
 * rather than run without the promise the key asked for; a request carrying
 * none is not affected.
 */
object Idempotency {

    const val HEADER = "Idempotency-Key"
    const val REPLAYED_HEADER = "Idempotent-Replayed"

    /** How long an answered request is remembered. */
    const val TTL_SECONDS = 24 * 3600L

    /** How long a request being answered holds its key, should its answer never come. */
    const val IN_FLIGHT_TTL_SECONDS = 300L

    /** The longest key accepted. */
    const val MAX_KEY_LENGTH = 128

    private val log = LoggerFactory.getLogger(Idempotency::class.java)

    private var redisProvider: (() -> RedisCommands<String, String>)? = null
    private var maxBodyBytes: Long = AppConfig.DEFAULT_MAX_REQUEST_BODY_BYTES

    /** Sets the store, and the request-body cap the fingerprint's read holds to. Call once at startup. */
    fun init(redis: (() -> RedisCommands<String, String>)?, maxBodyBytes: Long) {
        this.redisProvider = redis
        this.maxBodyBytes = maxBodyBytes
    }

    /** A request being answered under a key: where it is kept, and the token that says it is this one. */
    internal class InFlight(val storeKey: String, val fingerprint: String, val token: String) {
        @Volatile var answer: JsonObject? = null
        @Volatile var unkeepable = false
    }

    private val inFlightKey = AttributeKey<InFlight>("IdempotencyInFlight")

    /**
     * Starts an idempotent request, before its handler: does nothing when
     * [call] carries no key; answers [call] itself with the remembered answer
     * when there is one (the caller then stops); otherwise marks the key as in
     * flight and the call to be remembered once answered ([capture], [settle]).
     * Throws the refusals: 400 for a malformed key, 409, 422, 503.
     */
    internal suspend fun begin(call: ApplicationCall, apiKeyId: UUID, canonicalPath: String) {
        val key = call.request.headers[HEADER] ?: return
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH || key.any { it !in ' '..'~' }) {
            throw fieldError(HEADER) { put("max", MAX_KEY_LENGTH) }
        }
        val redis = redisProvider ?: throw unavailable(call)
        val fingerprint = fingerprint(call, canonicalPath)
        val storeKey = "idempotency:$apiKeyId:${sha256(key.toByteArray())}"
        val token = UUID.randomUUID().toString()
        val marker = buildJsonObject {
            put("state", "pending")
            put("fp", fingerprint)
            put("token", token)
        }.toString()

        // Twice at most: a remembered request that lapses between the two
        // calls below leaves the key free, and the second try takes it.
        repeat(2) {
            val existing = try {
                val commands = redis()
                if (commands.set(storeKey, marker, SetArgs().nx().ex(IN_FLIGHT_TTL_SECONDS)) != null) {
                    call.attributes.put(inFlightKey, InFlight(storeKey, fingerprint, token))
                    return
                }
                commands.get(storeKey)
            } catch (e: Exception) {
                log.warn("idempotency store unavailable: {}", e.message)
                throw unavailable(call)
            } ?: return@repeat
            val record = runCatching { Json.parseToJsonElement(existing) as JsonObject }.getOrNull() ?: return@repeat
            if (record["fp"]?.jsonPrimitive?.contentOrNull != fingerprint) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, ErrorCodes.IDEMPOTENCY_KEY_REUSED)
            }
            if (record["state"]?.jsonPrimitive?.contentOrNull != "done") {
                throw ConflictException(ErrorCodes.IDEMPOTENCY_IN_PROGRESS)
            }
            replay(call, record)
            return
        }
        throw ConflictException(ErrorCodes.IDEMPOTENCY_IN_PROGRESS)
    }

    /**
     * Keeps [content], the answer [call] is about to send, if [call] is an
     * idempotent request being answered. Only a body held in memory can be
     * kept; anything else marks the request as not to be remembered.
     */
    internal fun capture(call: ApplicationCall, content: OutgoingContent) {
        val inFlight = call.attributes.getOrNull(inFlightKey) ?: return
        val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
        val body = when (content) {
            is OutgoingContent.ByteArrayContent -> content.bytes()
            is OutgoingContent.NoContent -> ByteArray(0)
            else -> {
                inFlight.unkeepable = true
                return
            }
        }
        inFlight.answer = buildJsonObject {
            put("state", "done")
            put("fp", inFlight.fingerprint)
            put("status", status.value)
            content.contentType?.let { put("type", it.toString()) }
            put("body", Base64.getEncoder().encodeToString(body))
        }
    }

    /**
     * After [call] was answered — or cancelled before it was: remembers the
     * answer, or lets the key go when there is nothing to remember — a server
     * error, an answer that could not be kept, or none at all. Never throws:
     * the answer has been sent either way. Runs once per call.
     */
    internal fun settle(call: ApplicationCall) {
        val inFlight = call.attributes.getOrNull(inFlightKey) ?: return
        // Once: the answer's hook and a cancelled call can both get here.
        call.attributes.remove(inFlightKey)
        val redis = redisProvider ?: return
        try {
            val answer = inFlight.answer
            val status = answer?.get("status")?.jsonPrimitive?.intOrNull
            if (answer != null && !inFlight.unkeepable && status != null && status < 500) {
                redis().set(inFlight.storeKey, answer.toString(), SetArgs().ex(TTL_SECONDS))
            } else {
                // Only the marker this request set: one that lapsed and was
                // taken by a later request is that request's.
                redis().eval<Long>(RELEASE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(inFlight.storeKey), inFlight.token)
            }
        } catch (e: Exception) {
            log.warn("could not settle idempotency key {}: {}", inFlight.storeKey, e.message)
        }
    }

    private suspend fun replay(call: ApplicationCall, record: JsonObject) {
        val status = HttpStatusCode.fromValue(record["status"]?.jsonPrimitive?.intOrNull ?: 200)
        val type = record["type"]?.jsonPrimitive?.contentOrNull?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        val body = record["body"]?.jsonPrimitive?.contentOrNull?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0)
        call.response.header(REPLAYED_HEADER, "true")
        call.respondBytes(body, type, status)
    }

    /**
     * The request's fingerprint: method, path and query, and a hash of the
     * body. Reading the body to hash it consumes it, so the bytes are handed
     * back to the request for the handler — held to the request-body cap, as
     * the handler's own read would be.
     *
     * Handing them back is `setReceiveChannel`, which Ktor marks internal: it
     * is how its own double-receive works, and the alternative — that plugin,
     * installed for every call — keeps the raw body of every request, ahead of
     * the request-body cap. `ApiKeyResourcesTest` sends a body through this
     * and checks the handler got it, so an upgrade that changes it fails there.
     */
    @OptIn(InternalAPI::class)
    private suspend fun fingerprint(call: ApplicationCall, canonicalPath: String): String {
        val request = call.request as? PipelineRequest
            ?: throw IllegalStateException("the request body cannot be read twice here")
        val bytes = request.receiveChannel().readRemaining(maxBodyBytes + 1).readByteArray()
        if (bytes.size > maxBodyBytes) throw PayloadTooLargeException(maxBodyBytes)
        request.setReceiveChannel(ByteReadChannel(bytes))
        val query = call.request.local.uri.substringAfter('?', "")
        return sha256("${call.request.local.method.value}\n$canonicalPath\n$query\n${sha256(bytes)}".toByteArray())
    }

    private fun unavailable(call: ApplicationCall): ApiException {
        call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS.toString())
        return ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.IDEMPOTENCY_UNAVAILABLE)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val RETRY_AFTER_SECONDS = 5

    /** KEYS[1] = the key, ARGV[1] = this request's token: delete it only while it is still this request's marker. */
    private val RELEASE_SCRIPT = """
        local v = redis.call('get', KEYS[1])
        if v and string.find(v, ARGV[1], 1, true) then
            return redis.call('del', KEYS[1])
        end
        return 0
    """.trimIndent()
}
