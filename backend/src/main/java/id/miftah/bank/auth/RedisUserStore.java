package id.miftah.bank.auth;

import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import id.miftah.bank.common.DependencyUnavailableException;
import id.miftah.bank.config.BankProperties.SeedUser;

/**
 * Data user (untuk login) disimpan di Redis sebagai hash: key "user:{username}"
 * dengan field passwordHash, fullName, accountNo.
 * Semua error koneksi Redis diubah jadi {@link DependencyUnavailableException} (HTTP 503).
 */
@Repository
public class RedisUserStore {

    private static final String KEY_PREFIX = "user:";

    public record StoredUser(String username, String passwordHash, String fullName, String accountNo) {
    }

    private final StringRedisTemplate redis;

    public RedisUserStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<StoredUser> find(String username) {
        try {
            Map<Object, Object> data = redis.opsForHash().entries(KEY_PREFIX + username);
            if (data.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new StoredUser(
                    username,
                    (String) data.get("passwordHash"),
                    (String) data.get("fullName"),
                    (String) data.get("accountNo")));
        } catch (DataAccessException e) {
            throw DependencyUnavailableException.redis(e);
        }
    }

    /** Idempotent: hanya menulis kalau key belum ada. Return true kalau user baru ditulis. */
    public boolean seedIfMissing(SeedUser user) {
        try {
            String key = KEY_PREFIX + user.username();
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                return false;
            }
            redis.opsForHash().putAll(key, Map.of(
                    "passwordHash", Passwords.sha256Hex(user.password()),
                    "fullName", user.fullName(),
                    "accountNo", user.accountNo()));
            return true;
        } catch (DataAccessException e) {
            throw DependencyUnavailableException.redis(e);
        }
    }

    /** Cek koneksi Redis (untuk endpoint diagnostik). */
    public boolean ping() {
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (DataAccessException e) {
            return false;
        }
    }
}
