package id.miftah.bank.common;

import org.springframework.http.HttpStatus;

/**
 * A business error with an HTTP status and a clear error code, for example 401 INVALID_CREDENTIALS.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
