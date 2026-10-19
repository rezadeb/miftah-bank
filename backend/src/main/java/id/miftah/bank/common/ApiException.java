package id.miftah.bank.common;

import org.springframework.http.HttpStatus;

/**
 * Error bisnis dengan kode HTTP + kode error yang jelas (mis. 401 INVALID_CREDENTIALS).
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
