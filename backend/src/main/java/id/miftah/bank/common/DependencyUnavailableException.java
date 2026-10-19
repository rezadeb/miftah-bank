package id.miftah.bank.common;

import org.springframework.http.HttpStatus;

/**
 * Redis or PostgreSQL cannot be reached. This always becomes HTTP 503.
 * Error codes: REDIS_UNAVAILABLE and DB_UNAVAILABLE.
 */
public class DependencyUnavailableException extends ApiException {

    public static final String REDIS = "REDIS_UNAVAILABLE";
    public static final String DATABASE = "DB_UNAVAILABLE";

    public DependencyUnavailableException(String code, String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, message);
        initCause(cause);
    }

    public static DependencyUnavailableException redis(Throwable cause) {
        return new DependencyUnavailableException(REDIS,
                "Cannot reach Redis, so the login check failed", cause);
    }

    public static DependencyUnavailableException database(Throwable cause) {
        return new DependencyUnavailableException(DATABASE,
                "Database error: cannot reach PostgreSQL", cause);
    }
}
