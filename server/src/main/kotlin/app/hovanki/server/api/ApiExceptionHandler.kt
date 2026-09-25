package app.hovanki.server.api

import app.hovanki.server.game.GameException
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import jakarta.servlet.ServletException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Every error leaves the server as the shared [ApiError] JSON, so clients handle them uniformly. */
@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(GameException::class)
    fun gameError(e: GameException): ResponseEntity<ApiError> = error(e.code, e.message.orEmpty())

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<ApiError> =
        error(ErrorCode.BAD_REQUEST, "Malformed request body")

    /** Spring's own client errors: unknown path, wrong HTTP method or content type. Keeps Spring's status. */
    @ExceptionHandler(ServletException::class, ErrorResponseException::class)
    fun requestRejected(e: Exception): ResponseEntity<ApiError> {
        val status = (e as? ErrorResponse)?.statusCode
        if (status == null || !status.is4xxClientError) return unexpected(e)
        val code = if (status.value() == HttpStatus.NOT_FOUND.value()) ErrorCode.NOT_FOUND else ErrorCode.BAD_REQUEST
        val reason = HttpStatus.resolve(status.value())?.reasonPhrase ?: "Bad request"
        return ResponseEntity.status(status).body(ApiError(code, reason))
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled error", e)
        return error(ErrorCode.INTERNAL, "Internal error")
    }

    private fun error(code: ErrorCode, message: String) =
        ResponseEntity.status(code.httpStatus()).body(ApiError(code, message))

    private fun ErrorCode.httpStatus(): HttpStatus = when (this) {
        ErrorCode.BAD_REQUEST -> HttpStatus.BAD_REQUEST
        ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
        ErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN
        ErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND
        ErrorCode.WRONG_STATE -> HttpStatus.CONFLICT
        ErrorCode.NO_LOCATION, ErrorCode.TOO_FAR, ErrorCode.INVALID_CODE -> HttpStatus.valueOf(422)
        ErrorCode.INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR
    }
}
