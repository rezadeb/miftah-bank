package id.miftah.bank.common;

import org.springframework.http.HttpStatus;

/**
 * Dependency (Redis / PostgreSQL) tidak bisa dihubungi -> HTTP 503.
 * Kode error: REDIS_UNAVAILABLE atau DB_UNAVAILABLE.
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
                "Redis tidak dapat dihubungi, validasi login gagal", cause);
    }

    public static DependencyUnavailableException database(Throwable cause) {
        return new DependencyUnavailableException(DATABASE,
                "Database error: PostgreSQL tidak dapat dihubungi", cause);
    }
}
