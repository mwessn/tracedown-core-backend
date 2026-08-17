package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.pfs.PfsFilter
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.PfsSorter
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parses PFS query parameters from an HTTP request.
 *
 * Query params:
 * - `page` (int, default 1) — 1-indexed page number
 * - `pageSize` (int, default 50, max 100) — items per page
 * - `filters` (JSON array) — `[{"table":"...","column":"...","operator":"eq","value":"..."}]`
 * - `sorters` (JSON array) — `[{"table":"...","column":"...","order":"asc"}]`
 *
 * Returns default PfsParams when no params are present (backward compatible).
 */
fun parsePfsParams(call: ApplicationCall): PfsParams {
    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
    val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 50

    val filters = call.request.queryParameters["filters"]?.let { raw ->
        try {
            json.decodeFromString<List<PfsFilter>>(raw)
        } catch (e: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    } ?: emptyList()

    val sorters = call.request.queryParameters["sorters"]?.let { raw ->
        try {
            json.decodeFromString<List<PfsSorter>>(raw)
        } catch (e: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    } ?: emptyList()

    if (page < 1) throw BadRequestException(ErrorCodes.FIELD_INVALID)
    if (pageSize < 1 || pageSize > 1000) throw BadRequestException(ErrorCodes.FIELD_INVALID)

    return PfsParams(page = page, pageSize = pageSize, filters = filters, sorters = sorters)
}
