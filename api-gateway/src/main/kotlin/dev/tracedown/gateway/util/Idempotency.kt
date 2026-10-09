package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.util.AttributeKey
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
 * what was asked (the method, the path, its query parameters in a fixed order,
 * the body's content type and a hash of the body — the *fingerprint*) and,
 * once it is answered, the answer (status, type, body). After that, the same
 * key:
 *
 *  - with the same fingerprint is answered with the remembered answer, and
 *    `Idempotent-Replayed: true`, without the handler running again — a
 *    refusal (4xx) as much as a success: a new attempt after changing
 *    anything needs a new key;
 *  - with a different fingerprint is 422 `idempotency_key_reused`: the key
 *    names another request, and guessing which was meant is not this layer's
 *    to do;
 *  - while the first is still being answered, 409 `idempotency_in_progress`,
 *    with `Retry-After`.
 *
 * **Once means once.** The answer is written down when it is ready to be sent
 * ([capture], before the engine writes a byte), so a repeat that arrives the
 * moment the first answer does is replayed, and an answer the engine then
 * fails to deliver is still remembered. A call that got as far as its handler
 * and never produced an answer — the client went away mid-call — keeps its key
 * held for [IN_FLIGHT_TTL_SECONDS]: whatever it did may have been done, and a
 * retry is told to wait (409) rather than allowed to do it again.
 *
 * **Not remembered**: a server error (5xx) — the next request with its key
 * runs; an answer larger than [MAX_STORED_BODY_BYTES] or one that is streamed —
 * the key is let go, and a repeat runs again. And nothing past
 * [MAX_RECORDS_PER_KEY] new keys per API key in 24 hours: past that, requests
 * are answered normally and not remembered.
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

    /** The largest answer body kept for a replay. */
    const val MAX_STORED_BODY_BYTES = 64 * 1024

    /** New keys remembered per API key in [TTL_SECONDS]. */
    const val MAX_RECORDS_PER_KEY = 1000

    /** [MAX_RECORDS_PER_KEY], settable so tests need not make a thousand requests. */
    internal var recordsPerKey = MAX_RECORDS_PER_KEY

    /** Seconds a request told the key is in flight waits before asking again. */
    private const val IN_PROGRESS_RETRY_SECONDS = 1

    private const val UNAVAILABLE_RETRY_SECONDS = 5

    private val log = LoggerFactory.getLogger(Idempotency::class.java)

    private var redisProvider: (() -> RedisCommands<String, String>)? = null
    private var maxBodyBytes: Long = AppConfig.DEFAULT_MAX_REQUEST_BODY_BYTES

    /** Sets the store, and the request-body cap the fingerprint's read holds to. Call once at startup. */
    fun init(redis: (() -> RedisCommands<String, String>)?, maxBodyBytes: Long) {
        this.redisProvider = redis
        this.maxBodyBytes = maxBodyBytes
    }

    /**
     * Lets the body of an idempotent request be read twice — once for its
     * fingerprint, once by its handler — with Ktor's DoubleReceive, which
     * keeps the raw body in memory for those requests and no other. The
     * request-body cap still bounds it: the cap's transformation runs on every
     * read of the kept body, and a body declared larger than the cap is
     * refused before anything reads it. A body sent without a length is kept
     * as far as the cap's read takes it (DoubleReceive's own `maxSize` would
     * refuse to keep it at all, and the handler's read would then fail).
     */
    fun installBodyCache(application: Application) {
        application.install(DoubleReceive) {
            excludeFromCache { call, _ -> !carriesKey(call) }
        }
    }

    /** A POST into the key-authenticated API carrying an `Idempotency-Key`, of a size the API accepts. */
    private fun carriesKey(call: ApplicationCall): Boolean =
        call.request.httpMethod == HttpMethod.Post &&
            call.request.headers[HEADER] != null &&
            ApiNamespace.isPublicUri(call.request.local.uri) &&
            (call.request.contentLength() ?: 0L) <= maxBodyBytes

    /** A request being answered under a key: where it is kept, and the token that says it is this one. */
    private class InFlight(val storeKey: String, val fingerprint: String, val token: String)

    private val inFlightKey = AttributeKey<InFlight>("IdempotencyInFlight")

    /**
     * Starts an idempotent request, before its handler: does nothing when
     * [call] carries no key; answers [call] itself with the remembered answer
     * when there is one (the caller then stops); otherwise holds the key for
     * this call until its answer is captured ([capture]). Throws the
     * refusals: 400 for a malformed key, 409, 422, 503.
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
                    if (withinAllowance(commands, apiKeyId)) {
                        call.attributes.put(inFlightKey, InFlight(storeKey, fingerprint, token))
                    } else {
                        release(commands, storeKey, token)
                    }
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
            if (record["state"]?.jsonPrimitive?.contentOrNull != "done") throw inProgress(call)
            replay(call, record)
            return
        }
        throw inProgress(call)
    }

    /**
     * Whether [apiKeyId] may have one more key remembered today. Counted per
     * API key in a window of [TTL_SECONDS] from its first; past the allowance
     * a request is answered as if it carried no key, and the first one past it
     * is logged.
     */
    private fun withinAllowance(commands: RedisCommands<String, String>, apiKeyId: UUID): Boolean {
        val countKey = "idempotency_count:$apiKeyId"
        val count = commands.incr(countKey)
        if (count == 1L) commands.expire(countKey, TTL_SECONDS)
        if (count == recordsPerKey + 1L) {
            log.warn("API key {} passed {} remembered Idempotency-Keys in a day; further ones are not remembered", apiKeyId, recordsPerKey)
        }
        return count <= recordsPerKey
    }

    /**
     * Writes down [content], the answer [call] is about to send, if [call] is
     * an idempotent request being answered — before the engine sends it, and
     * whether or not the engine then manages to. An answer that is not to be
     * remembered (a server error, a streamed body, one over
     * [MAX_STORED_BODY_BYTES]) lets the key go instead. Never throws: the
     * answer goes out either way.
     */
    internal fun capture(call: ApplicationCall, content: OutgoingContent) {
        val inFlight = call.attributes.getOrNull(inFlightKey) ?: return
        call.attributes.remove(inFlightKey)
        val redis = redisProvider ?: return
        val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
        val body = when (content) {
            is OutgoingContent.ByteArrayContent -> content.bytes()
            is OutgoingContent.NoContent -> ByteArray(0)
            else -> null
        }
        // A call cancelled under its handler — its client went away — that
        // the error pages still try to answer is not an answer: what the
        // handler did may have been done, so the key stays held.
        if (call.coroutineContext[kotlinx.coroutines.Job]?.isCancelled == true) return
        try {
            val commands = redis()
            if (body == null || status.value >= 500 || body.size > MAX_STORED_BODY_BYTES) {
                release(commands, inFlight.storeKey, inFlight.token)
                return
            }
            val answer = buildJsonObject {
                put("state", "done")
                put("fp", inFlight.fingerprint)
                put("status", status.value)
                content.contentType?.let { put("type", it.toString()) }
                put("body", Base64.getEncoder().encodeToString(body))
            }
            commands.set(inFlight.storeKey, answer.toString(), SetArgs().ex(TTL_SECONDS))
        } catch (e: Exception) {
            // The key stays held until its in-flight bound: a retry waits
            // rather than repeats.
            log.warn("could not record the answer under idempotency key {}: {}", inFlight.storeKey, e.message)
        }
    }

    /** Lets the key go — only while it is still this request's marker. */
    private fun release(commands: RedisCommands<String, String>, storeKey: String, token: String) {
        commands.eval<Long>(RELEASE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(storeKey), token)
    }

    private suspend fun replay(call: ApplicationCall, record: JsonObject) {
        val status = HttpStatusCode.fromValue(record["status"]?.jsonPrimitive?.intOrNull ?: 200)
        val type = record["type"]?.jsonPrimitive?.contentOrNull?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        val body = record["body"]?.jsonPrimitive?.contentOrNull?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0)
        call.response.header(REPLAYED_HEADER, "true")
        call.respondBytes(body, type, status)
    }

    /**
     * The request's fingerprint: method, path, query parameters (decoded and
     * sorted, so their order does not matter), the body's content type, and a
     * hash of the body. The body is read through the request-body cap, and
     * kept for the handler's own read ([installBodyCache]).
     */
    private suspend fun fingerprint(call: ApplicationCall, canonicalPath: String): String {
        val bytes = call.receiveChannel().readRemaining(maxBodyBytes + 1).readByteArray()
        val query = parseQueryString(call.request.local.uri.substringAfter('?', ""))
            .entries().flatMap { (name, values) -> values.map { "$name=$it" } }
            .sorted().joinToString("&")
        val type = call.request.headers[HttpHeaders.ContentType].orEmpty()
        return sha256("${call.request.local.method.value}\n$canonicalPath\n$query\n$type\n${sha256(bytes)}".toByteArray())
    }

    private fun inProgress(call: ApplicationCall): ApiException {
        call.response.header(HttpHeaders.RetryAfter, IN_PROGRESS_RETRY_SECONDS.toString())
        return ConflictException(ErrorCodes.IDEMPOTENCY_IN_PROGRESS)
    }

    private fun unavailable(call: ApplicationCall): ApiException {
        call.response.header(HttpHeaders.RetryAfter, UNAVAILABLE_RETRY_SECONDS.toString())
        return ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.IDEMPOTENCY_UNAVAILABLE)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** KEYS[1] = the key, ARGV[1] = this request's token: delete it only while it is still this request's marker. */
    private val RELEASE_SCRIPT = """
        local v = redis.call('get', KEYS[1])
        if v and string.find(v, ARGV[1], 1, true) then
            return redis.call('del', KEYS[1])
        end
        return 0
    """.trimIndent()
}
