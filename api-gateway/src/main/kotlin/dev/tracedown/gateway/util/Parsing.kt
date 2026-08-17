package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import io.ktor.server.request.receive
import java.util.UUID

/** Parses a UUID string, throwing [BadRequestException] with a descriptive message on failure. */
fun parseUuid(value: String?, label: String): UUID {
    return try {
        UUID.fromString(value)
    } catch (e: Exception) {
        throw BadRequestException(ErrorCodes.INVALID_UUID)
    }
}

/** Receives a typed request body, throwing [BadRequestException] on parse failure. */
suspend inline fun <reified T : Any> tryReceive(call: io.ktor.server.application.ApplicationCall): T {
    return try {
        call.receive<T>()
    } catch (e: Exception) {
        throw BadRequestException(ErrorCodes.INVALID_REQUEST_BODY)
    }
}
