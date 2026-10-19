package id.miftah.bank;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import id.miftah.bank.account.AccountRepository;
import id.miftah.bank.auth.RedisUserStore;

@RestController
public class HealthController {

    private final RedisUserStore redisUsers;
    private final AccountRepository accounts;

    public HealthController(RedisUserStore redisUsers, AccountRepository accounts) {
        this.redisUsers = redisUsers;
        this.accounts = accounts;
    }

    /**
     * Liveness/readiness untuk Kubernetes. SENGAJA tidak mengecek Redis/Postgres:
     * kalau dependency mati, pod backend tetap Ready dan mengembalikan HTTP 503 per request
     * (bukan di-restart atau dikeluarkan dari Service), sehingga skenario demo konsisten.
     */
    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "UP");
    }

    /** Diagnostik manual: status koneksi ke Redis dan PostgreSQL. */
    @GetMapping("/api/health/dependencies")
    public Map<String, String> dependencies() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("redis", redisUsers.ping() ? "UP" : "DOWN");
        result.put("postgres", accounts.ping() ? "UP" : "DOWN");
        return result;
    }
}
