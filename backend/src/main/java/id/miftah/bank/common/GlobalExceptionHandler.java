package id.miftah.bank.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into the same JSON format (see {@link ErrorResponse}).
 *
 * It extends {@link ResponseEntityExceptionHandler} on purpose. This keeps the correct status for
 * errors that Spring MVC raises itself (404 unknown path, 405 wrong method, 400 bad parameter).
 * Without it, the catch-all handler below would turn all of them into HTTP 500, and Dynatrace
 * would show failures that are not real.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Redis or PostgreSQL is down: HTTP 503. Logged as ERROR with the exception, so it is easy to find. */
    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDependency(DependencyUnavailableException e) {
        log.error("Dependency unavailable [{}]: {}", e.code(), e.getMessage(), e);
        return build(e);
    }

    /** Expected business errors (wrong password, bad amount, ...). Logged as WARN, no stack trace. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        log.warn("Request rejected [{} {}]: {}", e.status().value(), e.code(), e.getMessage());
        return build(e);
    }

    /** Anything else is a real bug: HTTP 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "INTERNAL_ERROR", "Something went wrong on the server"));
    }

    /** Called for errors raised by Spring MVC itself. Replaces Spring's default body with our format. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        int status = statusCode.value();
        HttpStatus resolved = HttpStatus.resolve(status);
        String code = resolved != null ? resolved.name() : "HTTP_" + status;

        String message;
        if (ex instanceof HttpMessageNotReadableException) {
            message = "Invalid request body";
        } else if (body instanceof ProblemDetail problem && problem.getDetail() != null) {
            message = problem.getDetail();
        } else {
            message = resolved != null ? resolved.getReasonPhrase() : "Request failed";
        }

        if (status >= 500) {
            log.error("Request failed [{} {}]: {}", status, code, message, ex);
        } else {
            log.warn("Request rejected [{} {}]: {}", status, code, message);
        }
        return ResponseEntity.status(statusCode).headers(headers).body(ErrorResponse.of(status, code, message));
    }

    private static ResponseEntity<ErrorResponse> build(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(ErrorResponse.of(e.status().value(), e.code(), e.getMessage()));
    }
}
