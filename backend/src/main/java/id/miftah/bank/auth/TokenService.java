package id.miftah.bank.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import id.miftah.bank.common.ApiException;
import id.miftah.bank.config.BankProperties;

/**
 * Simple login token, signed with HMAC-SHA256 and stateless:
 * base64url("username|expiryEpochSeconds") + "." + base64url(signature).
 *
 * Why stateless: Redis is only used at LOGIN time. If the Redis pod dies, users who are already
 * logged in can still check balance and transfer. Each dependency fails on its own.
 * DEMO ONLY. This is not a standard JWT.
 */
@Service
public class TokenService {

    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;
    private final Clock clock;

    @Autowired
    public TokenService(BankProperties props) {
        this(props.tokenSecret(), props.tokenTtlMinutes(), Clock.systemUTC());
    }

    // Used by tests, so the clock can be controlled.
    TokenService(String secret, int ttlMinutes, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlMinutes * 60L;
        this.clock = clock;
    }

    public String issue(String username) {
        long expiresAt = clock.instant().getEpochSecond() + ttlSeconds;
        String payload = ENC.encodeToString((username + "|" + expiresAt).getBytes(StandardCharsets.UTF_8));
        return payload + "." + ENC.encodeToString(sign(payload));
    }

    /** Reads the username from the "Authorization: Bearer ..." header. Throws 401 if it is missing, wrong, or expired. */
    public String requireUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized("No token. Please log in");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw unauthorized("Invalid token");
        }

        String payload = token.substring(0, dot);
        byte[] givenSignature;
        String decoded;
        try {
            givenSignature = DEC.decode(token.substring(dot + 1));
            decoded = new String(DEC.decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized("Invalid token");
        }
        if (!MessageDigest.isEqual(givenSignature, sign(payload))) {
            throw unauthorized("Invalid token");
        }

        int separator = decoded.lastIndexOf('|');
        if (separator <= 0) {
            throw unauthorized("Invalid token");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(decoded.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw unauthorized("Invalid token");
        }
        if (clock.instant().getEpochSecond() > expiresAt) {
            throw unauthorized("Session expired. Please log in again");
        }
        return decoded.substring(0, separator);
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC is not available", e);
        }
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }
}
