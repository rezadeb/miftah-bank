package id.miftah.bank.common;

import java.time.Instant;

/** The JSON error format used by every endpoint. */
public record ErrorResponse(int status, String code, String message, Instant timestamp) {

    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(status, code, message, Instant.now());
    }
}
