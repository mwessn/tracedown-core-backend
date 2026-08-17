package dev.tracedown.gateway.routes.internal

import io.lettuce.core.api.sync.RedisCommands
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Serves one-time health challenge tokens for the agent health check flow.
 *
 * The scheduler stores a token in Redis A, then tells the agent to
 * fetch it from this endpoint via a Lace script. This proves the
 * agent's executor and network path are functional.
 *
 * Redis is accessed lazily to avoid connecting at startup (tests may
 * not have Redis available).
 */
fun Route.internalHealthTokenRoutes(redisProvider: () -> RedisCommands<String, String>) {
    route("/internal/health") {
        /** Returns the one-time token for a health challenge. */
        get("/token/{challengeId}") {
            val challengeId = call.parameters["challengeId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing challengeId"))

            val token = redisProvider().get("health:token:$challengeId")
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Token not found or expired"))

            call.respond(mapOf("token" to token))
        }
    }
}
