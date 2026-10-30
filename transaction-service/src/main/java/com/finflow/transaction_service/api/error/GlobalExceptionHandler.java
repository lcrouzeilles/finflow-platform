package com.finflow.transaction_service.api.error;

import com.finflow.transaction_service.exception.AccountNotFoundException;
import com.finflow.transaction_service.exception.IdempotencyKeyConflictException;
import com.finflow.transaction_service.exception.InsufficientFundsException;
import com.finflow.transaction_service.exception.InvalidTransferException;
import com.finflow.transaction_service.exception.OwnerNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single place that maps exceptions to the {@link ApiErrorResponse} contract.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that every standard
 * Spring MVC exception (missing header, unreadable body, type mismatch,
 * unsupported method, unknown route, ...) also gets the same JSON body
 * instead of the framework default, and so that the generic fallback below
 * never swallows them into a 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------
    // Framework exceptions (Spring MVC)
    // ---------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        fieldErrors.put(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                );

        ApiErrorResponse body = body(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                path(request),
                fieldErrors
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .headers(headers)
                .body(body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());

        String message = status.is5xxServerError()
                ? status.getReasonPhrase()
                : safeMessage(exception, status.getReasonPhrase());

        if (status.is5xxServerError()) {
            log.error("Unhandled framework exception", exception);
        }

        return ResponseEntity.status(status)
                .headers(headers)
                .body(body(status, message, path(request), Map.of()));
    }

    // ---------------------------------------------------------------
    // Security
    // ---------------------------------------------------------------

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        return respond(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required",
                request
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDeniedException(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return respond(
                HttpStatus.FORBIDDEN,
                safeMessage(exception, "Access is denied"),
                request
        );
    }

    // ---------------------------------------------------------------
    // Domain
    // ---------------------------------------------------------------

    @ExceptionHandler(OwnerNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleOwnerNotFoundException(
            OwnerNotFoundException exception,
            HttpServletRequest request
    ) {
        return respond(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountNotFoundException(
            AccountNotFoundException exception,
            HttpServletRequest request
    ) {
        return respond(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientFunds(
            InsufficientFundsException exception,
            HttpServletRequest request
    ) {
        return respond(
                HttpStatus.UNPROCESSABLE_CONTENT,
                exception.getMessage(),
                request
        );
    }

    /**
     * Business-rule violations on a syntactically valid request (same
     * source and destination, currency mismatch, non-positive amount).
     */
    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTransfer(
            InvalidTransferException exception,
            HttpServletRequest request
    ) {
        return respond(
                HttpStatus.UNPROCESSABLE_CONTENT,
                exception.getMessage(),
                request
        );
    }

    /**
     * Malformed input that passed bean validation but is rejected by the
     * domain (e.g. blank or oversized Idempotency-Key header).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return respond(
                HttpStatus.BAD_REQUEST,
                safeMessage(exception, "Invalid request"),
                request
        );
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyKeyConflict(
            IdempotencyKeyConflictException exception,
            HttpServletRequest request
    ) {
        return respond(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    /**
     * Unique/foreign key violations that were not resolved by the domain
     * (e.g. account number collision). The SQL detail is logged, not
     * returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "Data integrity violation on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return respond(
                HttpStatus.CONFLICT,
                "Request conflicts with existing data",
                request
        );
    }

    // ---------------------------------------------------------------
    // Fallback
    // ---------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unhandled exception on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request
        );
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static ResponseEntity<ApiErrorResponse> respond(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status)
                .body(body(status, message, request.getRequestURI(), Map.of()));
    }

    private static ApiErrorResponse body(
            HttpStatus status,
            String message,
            String path,
            Map<String, String> fieldErrors
    ) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                reasonPhrase(status),
                message,
                path,
                fieldErrors
        );
    }

    private static String reasonPhrase(HttpStatus status) {
        // Keep the historical wording for 422 used by this API.
        return status == HttpStatus.UNPROCESSABLE_CONTENT
                ? "Unprocessable Content"
                : status.getReasonPhrase();
    }

    private static String safeMessage(Exception exception, String fallback) {
        String message = exception.getMessage();
        return (message == null || message.isBlank()) ? fallback : message;
    }

    private static String path(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            return servletRequest.getRequest().getRequestURI();
        }
        return request.getDescription(false).replaceFirst("^uri=", "");
    }
}
