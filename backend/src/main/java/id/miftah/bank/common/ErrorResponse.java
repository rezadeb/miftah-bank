package id.miftah.bank.common;

import java.time.Instant;

/** Format error JSON yang sama untuk semua endpoint. */
public record ErrorResponse(int status, String code, String message, Instant timestamp) {

    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(status, code, message, Instant.now());
    }
}
