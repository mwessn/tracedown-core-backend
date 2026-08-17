package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import io.ktor.http.HttpStatusCode

open class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String = code,
) : RuntimeException(message)

class UnauthorizedException(code: String = ErrorCodes.INVALID_TOKEN) :
    ApiException(HttpStatusCode.Unauthorized, code)

class ForbiddenException(code: String = ErrorCodes.FORBIDDEN) :
    ApiException(HttpStatusCode.Forbidden, code)

class NotFoundException(code: String = ErrorCodes.NOT_FOUND) :
    ApiException(HttpStatusCode.NotFound, code)

class BadRequestException(code: String = ErrorCodes.FIELD_INVALID) :
    ApiException(HttpStatusCode.BadRequest, code)

class ConflictException(code: String = ErrorCodes.ALREADY_EXISTS) :
    ApiException(HttpStatusCode.Conflict, code)

class TooManyRequestsException(code: String = ErrorCodes.RATE_LIMITED) :
    ApiException(HttpStatusCode.TooManyRequests, code)
