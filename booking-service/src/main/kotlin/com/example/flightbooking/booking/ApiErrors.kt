package com.example.flightbooking.booking

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.format.DateTimeParseException

class ApiException(val status: HttpStatus, val code: String, override val message: String) : RuntimeException(message)
data class ApiError(val code: String, val message: String)

@RestControllerAdvice
class ApiErrors {
    private val log = LoggerFactory.getLogger(javaClass)
    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException) = response(error.status, error.code, error.message)

    @ExceptionHandler(StatusRuntimeException::class, StatusException::class)
    fun grpc(error: Exception): ResponseEntity<ApiError> {
        val status = Status.fromThrowable(error)
        val http = when (status.code) {
            Status.Code.INVALID_ARGUMENT, Status.Code.OUT_OF_RANGE -> HttpStatus.BAD_REQUEST
            Status.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
            Status.Code.ALREADY_EXISTS, Status.Code.RESOURCE_EXHAUSTED, Status.Code.FAILED_PRECONDITION,
            Status.Code.ABORTED -> HttpStatus.CONFLICT
            // This is a service credential, not a credential of the REST caller.
            Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> HttpStatus.BAD_GATEWAY
            Status.Code.UNAVAILABLE, Status.Code.CANCELLED -> HttpStatus.SERVICE_UNAVAILABLE
            Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT
            else -> HttpStatus.BAD_GATEWAY
        }
        val message = when (http) {
            HttpStatus.BAD_GATEWAY -> "Flight service could not process the request"
            HttpStatus.SERVICE_UNAVAILABLE -> "Flight service is temporarily unavailable"
            HttpStatus.GATEWAY_TIMEOUT -> "Flight service request timed out"
            else -> status.description ?: status.code.name
        }
        return response(http, status.code.name, message)
    }

    @ExceptionHandler(CircuitOpenException::class)
    fun circuit(error: CircuitOpenException) = response(HttpStatus.SERVICE_UNAVAILABLE, "CIRCUIT_OPEN", error.message!!)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun bodyValidation(error: MethodArgumentNotValidException) = response(
        HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", error.bindingResult.fieldErrors.joinToString("; ") {
            "${it.field}: ${it.defaultMessage}"
        }
    )

    @ExceptionHandler(ConstraintViolationException::class, HandlerMethodValidationException::class,
        MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class,
        HttpMessageNotReadableException::class, IllegalArgumentException::class, DateTimeParseException::class)
    fun invalid(error: Exception) = response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid request parameters or body")

    @ExceptionHandler(DataAccessException::class)
    fun database(error: DataAccessException): ResponseEntity<ApiError> {
        log.error("Booking database operation failed", error)
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Booking storage is temporarily unavailable")
    }

    private fun response(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ApiError(code, message))
}
