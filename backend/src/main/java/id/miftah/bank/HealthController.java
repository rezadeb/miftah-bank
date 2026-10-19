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
     * Liveness and readiness check for Kubernetes.
     * It does NOT check Redis or PostgreSQL on purpose. If a database is down, the backend pod stays
     * Ready and answers HTTP 503 per request. It is not restarted and not removed from the Service,
     * so the failure demo stays predictable.
     */
    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "UP");
    }

    /** For manual checks: can the backend reach Redis and PostgreSQL right now? */
    @GetMapping("/api/health/dependencies")
    public Map<String, String> dependencies() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("redis", redisUsers.ping() ? "UP" : "DOWN");
        result.put("postgres", accounts.ping() ? "UP" : "DOWN");
        return result;
    }
}
