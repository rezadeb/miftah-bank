package id.miftah.bank.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import id.miftah.bank.common.ApiException;

class TokenServiceTest {

    private static final String SECRET = "unit-test-secret-at-least-32-characters-long";

    @Test
    void issuedTokenCanBeVerified() {
        TokenService service = new TokenService(SECRET, 60, Clock.systemUTC());
        String token = service.issue("demo");
        assertEquals("demo", service.requireUser("Bearer " + token));
    }

    @Test
    void missingHeaderIsRejectedWith401() {
        TokenService service = new TokenService(SECRET, 60, Clock.systemUTC());
        ApiException e = assertThrows(ApiException.class, () -> service.requireUser(null));
        assertEquals(HttpStatus.UNAUTHORIZED, e.status());
    }

    @Test
    void changedTokenIsRejectedWith401() {
        TokenService service = new TokenService(SECRET, 60, Clock.systemUTC());
        String token = service.issue("demo");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        ApiException e = assertThrows(ApiException.class, () -> service.requireUser("Bearer " + tampered));
        assertEquals(HttpStatus.UNAUTHORIZED, e.status());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = new TokenService("another-secret-that-is-also-32-chars-long", 60, Clock.systemUTC()).issue("demo");
        TokenService service = new TokenService(SECRET, 60, Clock.systemUTC());
        assertThrows(ApiException.class, () -> service.requireUser("Bearer " + token));
    }

    @Test
    void expiredTokenIsRejectedWith401() {
        Instant now = Instant.parse("2026-10-19T09:00:00Z");
        String token = new TokenService(SECRET, 60, Clock.fixed(now, ZoneOffset.UTC)).issue("demo");

        Clock later = Clock.fixed(now.plus(Duration.ofMinutes(61)), ZoneOffset.UTC);
        ApiException e = assertThrows(ApiException.class,
                () -> new TokenService(SECRET, 60, later).requireUser("Bearer " + token));
        assertEquals(HttpStatus.UNAUTHORIZED, e.status());
    }

    @Test
    void passwordHashMatchesOnlyTheRightPassword() {
        String hash = Passwords.sha256Hex("demo123");
        assertTrue(Passwords.matches("demo123", hash));
        assertFalse(Passwords.matches("wrong", hash));
        assertFalse(Passwords.matches("demo123", null));
    }
}
