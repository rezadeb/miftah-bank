package id.miftah.bank.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDependency(DependencyUnavailableException e) {
        // ERROR + exception supaya terlihat jelas sebagai exception/failure di Dynatrace
        log.error("Dependency tidak tersedia [{}]: {}", e.code(), e.getMessage(), e);
        return build(e);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        log.warn("Request ditolak [{} {}]: {}", e.status().value(), e.code(), e.getMessage());
        return build(e);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBadBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "BAD_REQUEST", "Format request tidak valid"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Error tidak terduga", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "INTERNAL_ERROR", "Terjadi kesalahan pada server"));
    }

    private static ResponseEntity<ErrorResponse> build(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(ErrorResponse.of(e.status().value(), e.code(), e.getMessage()));
    }
}
