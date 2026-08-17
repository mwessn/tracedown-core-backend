package dev.tracedown.gateway.routes.internal

import dev.tracedown.gateway.controllers.agents.AgentRegistrationController
import dev.tracedown.gateway.data.agents.AgentRegisterRequest
import dev.tracedown.gateway.data.agents.AgentRenewRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Internal routes for agent registration (not user-facing). */
fun Route.internalAgentRoutes() {
    route("/internal/agents") {
        /** Registers a new probe agent using a bootstrap token. */
        post("/register") {
            val request = call.receive<AgentRegisterRequest>()
            val response = AgentRegistrationController.register(request, request.agentUri)
            call.respond(response)
        }

        /**
         * Rotates the certificate of an already-registered agent. Gated by
         * proof-of-possession of the agent's current private key, not by a
         * bootstrap token — this is not a new-connection path.
         */
        post("/renew") {
            val request = call.receive<AgentRenewRequest>()
            val response = AgentRegistrationController.renew(request)
            call.respond(response)
        }
    }
}
