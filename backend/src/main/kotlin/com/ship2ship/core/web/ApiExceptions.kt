package com.ship2ship.core.web

import org.springframework.http.HttpStatus

/** Field-level error carried in a Problem Details response. */
data class FieldError(val field: String, val code: String, val message: String?)

/** Business error rendered as RFC 9457 Problem Details with a stable machine-readable [code]. */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val errors: List<FieldError> = emptyList(),
) : RuntimeException(message)

class NotFoundException(entity: String) :
    ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$entity was not found.")

class ConflictException(code: String, message: String, errors: List<FieldError> = emptyList()) :
    ApiException(HttpStatus.CONFLICT, code, message, errors)

class BadRequestException(code: String, message: String, errors: List<FieldError> = emptyList()) :
    ApiException(HttpStatus.BAD_REQUEST, code, message, errors)

class BusinessRuleException(code: String, message: String) :
    ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message)
