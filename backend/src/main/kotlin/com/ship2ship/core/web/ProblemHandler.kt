package com.ship2ship.core.web

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/** Renders every API error as RFC 9457 Problem Details with a stable `code` (and `errors` for field problems). */
@RestControllerAdvice
class ProblemHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException): ResponseEntity<ProblemDetail> =
        problem(ex.status, ex.code, ex.message, ex.errors)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission for this action.")

    @ExceptionHandler(AuthenticationException::class)
    fun handleUnauthenticated(ex: AuthenticationException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in.")

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleOptimisticLock(ex: ObjectOptimisticLockingFailureException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "STALE_VERSION", "The record was changed by someone else. Reload and try again.")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ProblemDetail> {
        log.warn("Data integrity violation: {}", ex.mostSpecificCause.message)
        return problem(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION", "The change conflicts with existing data.")
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parameter '${ex.name}' has an invalid value.")

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unhandled error", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.")
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException, headers: HttpHeaders, status: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any> {
        val errors = ex.bindingResult.fieldErrors.map { FieldError(it.field, it.code ?: "invalid", it.defaultMessage) } +
            ex.bindingResult.globalErrors.map { FieldError(it.objectName, it.code ?: "invalid", it.defaultMessage) }
        @Suppress("UNCHECKED_CAST")
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request has invalid fields.", errors)
            as ResponseEntity<Any>
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException, headers: HttpHeaders, status: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any> {
        @Suppress("UNCHECKED_CAST")
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body could not be read.")
            as ResponseEntity<Any>
    }

    override fun handleMaxUploadSizeExceededException(
        ex: MaxUploadSizeExceededException, headers: HttpHeaders, status: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any> {
        @Suppress("UNCHECKED_CAST")
        return problem(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "The file is larger than allowed.") as ResponseEntity<Any>
    }

    /** Adds a `code` to the framework's own Problem Details (405, 415, missing parameters, ...). */
    override fun createProblemDetail(
        ex: Exception, status: HttpStatusCode, defaultDetail: String, detailMessageCode: String?,
        detailMessageArguments: Array<out Any>?, request: WebRequest,
    ): ProblemDetail = super.createProblemDetail(ex, status, defaultDetail, detailMessageCode, detailMessageArguments, request)
        .apply { setProperty("code", HttpStatus.resolve(status.value())?.name ?: "HTTP_${status.value()}") }

    companion object {
        fun problemDetail(status: HttpStatus, code: String, detail: String, errors: List<FieldError> = emptyList()) =
            ProblemDetail.forStatusAndDetail(status, detail).apply {
                setProperty("code", code)
                if (errors.isNotEmpty()) setProperty("errors", errors)
            }

        fun problem(status: HttpStatus, code: String, detail: String, errors: List<FieldError> = emptyList()) =
            ResponseEntity.status(status).body(problemDetail(status, code, detail, errors))
    }
}
