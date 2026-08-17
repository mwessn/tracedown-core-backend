package dev.tracedown.gateway.routes.v1.bulk

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.data.bulk.BulkCallResult
import dev.tracedown.gateway.data.bulk.BulkRequest
import dev.tracedown.gateway.data.bulk.BulkResponse
import dev.tracedown.gateway.data.bulk.BulkSubResponse
import dev.tracedown.gateway.routes.v1.auth.requireAuth
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

private const val MAX_BULK_REQUESTS = 10

/**
 * @OpenAPITag Bulk
 * Execute multiple API calls in a single HTTP request.
 */
@Resource("/api/v1/bulk")
@Serializable
class Bulk

/** Registers the POST /bulk endpoint. */
fun Route.bulkRoutes() {
    /**
     * Executes multiple sub-requests in a single HTTP call.
     * Authenticates once; each sub-request reuses the same session.
     * Individual sub-request failures do not fail the entire batch.
     */
    post<Bulk> {
        val principal = requireAuth(call)
        val request = tryReceive<BulkRequest>(call)

        if (request.requests.size > MAX_BULK_REQUESTS) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        val results = request.requests.mapIndexed { index, subReq ->
            try {
                val body = BulkDispatcher.dispatch(subReq.method, subReq.url, subReq.body, principal)
                BulkCallResult(
                    index = index,
                    request = subReq,
                    response = BulkSubResponse(status = 200, body = body),
                )
            } catch (e: ApiException) {
                BulkCallResult(
                    index = index,
                    request = subReq,
                    response = BulkSubResponse(
                        status = e.status.value,
                        body = json.encodeToJsonElement(mapOf("error" to e.code)),
                    ),
                )
            } catch (e: Exception) {
                BulkCallResult(
                    index = index,
                    request = subReq,
                    response = BulkSubResponse(
                        status = 500,
                        body = json.encodeToJsonElement(mapOf("error" to ErrorCodes.INTERNAL_ERROR)),
                    ),
                )
            }
        }

        call.respond(BulkResponse(calls = results))
    }
}
