package id.miftah.bank.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import id.miftah.bank.common.ApiException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String username, String fullName, String accountNo) {
    }

    private final RedisUserStore users;
    private final TokenService tokens;

    public AuthController(RedisUserStore users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** Validasi username/password ke Redis. Redis mati -> 503 REDIS_UNAVAILABLE. */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String username = request.username() == null ? "" : request.username().trim().toLowerCase();
        String password = request.password() == null ? "" : request.password();
        if (username.isEmpty() || password.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Username dan password wajib diisi");
        }

        RedisUserStore.StoredUser user = users.find(username)
                .filter(u -> Passwords.matches(password, u.passwordHash()))
                .orElseThrow(() -> {
                    log.warn("Login gagal untuk user '{}': username atau password salah", username);
                    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                            "Username atau password salah");
                });

        log.info("Login berhasil untuk user '{}'", username);
        return new LoginResponse(tokens.issue(username), username, user.fullName(), user.accountNo());
    }
}
