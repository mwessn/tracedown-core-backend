package dev.tracedown.gateway.routes

import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.get

/**
 * @OpenAPITag Health
 * Health check endpoint.
 */
@Resource("/ping")
class Ping

fun Route.pingRoute() {
    get<Ping> {
        call.respond(mapOf("status" to "ok"))
    }
}
