package dev.tracedown.gateway.routes

import io.ktor.server.routing.Route
import io.ktor.server.routing.route

fun Route.v1(build: Route.() -> Unit) = route("/api/v1", build)
fun Route.v2(build: Route.() -> Unit) = route("/api/v2", build)
