package dev.tracedown.gateway.routes.v1.bulk

import dev.tracedown.gateway.context.AuthPrincipal
import dev.tracedown.gateway.util.NotFoundException
import kotlinx.serialization.json.JsonElement

/**
 * Registry of handlers that can be invoked via the POST /bulk endpoint.
 *
 * Each handler receives the authenticated principal, extracted path parameters,
 * and an optional request body. Handlers return [JsonElement] directly so
 * serialization happens at registration time where the concrete type is known.
 */
object BulkDispatcher {

    private data class RouteEntry(
        val method: String,
        val pattern: Regex,
        val paramNames: List<String>,
        val handler: suspend (AuthPrincipal, Map<String, String>, JsonElement?) -> JsonElement,
    )

    private val routes = mutableListOf<RouteEntry>()

    /** Registers a GET handler. */
    fun get(path: String, handler: suspend (AuthPrincipal, Map<String, String>) -> JsonElement) {
        register("GET", path) { principal, params, _ -> handler(principal, params) }
    }

    /** Registers a POST handler. */
    fun post(path: String, handler: suspend (AuthPrincipal, Map<String, String>, JsonElement?) -> JsonElement) {
        register("POST", path, handler)
    }

    /**
     * Dispatches a sub-request to the matching handler.
     * Throws [NotFoundException] if no handler matches.
     */
    suspend fun dispatch(
        method: String,
        url: String,
        body: JsonElement?,
        principal: AuthPrincipal,
    ): JsonElement {
        for (route in routes) {
            if (!route.method.equals(method, ignoreCase = true)) continue
            val match = route.pattern.matchEntire(url) ?: continue
            val params = route.paramNames.mapIndexed { i, name ->
                name to match.groupValues[i + 1]
            }.toMap()
            return route.handler(principal, params, body)
        }
        throw NotFoundException()
    }

    private fun register(
        method: String,
        path: String,
        handler: suspend (AuthPrincipal, Map<String, String>, JsonElement?) -> JsonElement,
    ) {
        val paramNames = mutableListOf<String>()
        val regexStr = path.split("/").joinToString("/") { segment ->
            if (segment.startsWith("{") && segment.endsWith("}")) {
                val name = segment.substring(1, segment.length - 1)
                paramNames.add(name)
                "([^/]+)"
            } else {
                Regex.escape(segment)
            }
        }
        routes.add(RouteEntry(method.uppercase(), Regex("^$regexStr$"), paramNames, handler))
    }
}
