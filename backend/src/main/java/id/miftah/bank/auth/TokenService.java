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
 * Token login sederhana (HMAC-SHA256, stateless): base64url("username|expiryEpochSec") + "." + base64url(signature).
 *
 * Sengaja stateless supaya Redis hanya dipakai saat LOGIN. Jadi kalau pod Redis mati,
 * user yang sudah login masih bisa cek saldo / transfer (failure domain terpisah per komponen).
 * DEMO ONLY - bukan JWT/standar production.
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

    TokenService(String secret, int ttlMinutes, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlMinutes * 60L;
        this.clock = clock;
    }

    public String issue(String username) {
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        String payload = ENC.encodeToString((username + "|" + exp).getBytes(StandardCharsets.UTF_8));
        return payload + "." + ENC.encodeToString(sign(payload));
    }

    /** Ambil username dari header "Authorization: Bearer ..."; lempar 401 kalau tidak valid / kedaluwarsa. */
    public String requireUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized("Token tidak ada, silakan login");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw unauthorized("Token tidak valid");
        }
        String payload = token.substring(0, dot);
        byte[] givenSig;
        String decoded;
        try {
            givenSig = DEC.decode(token.substring(dot + 1));
            decoded = new String(DEC.decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized("Token tidak valid");
        }
        if (!MessageDigest.isEqual(givenSig, sign(payload))) {
            throw unauthorized("Token tidak valid");
        }
        int sep = decoded.lastIndexOf('|');
        if (sep <= 0) {
            throw unauthorized("Token tidak valid");
        }
        long exp;
        try {
            exp = Long.parseLong(decoded.substring(sep + 1));
        } catch (NumberFormatException e) {
            throw unauthorized("Token tidak valid");
        }
        if (clock.instant().getEpochSecond() > exp) {
            throw unauthorized("Sesi berakhir, silakan login lagi");
        }
        return decoded.substring(0, sep);
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC tidak tersedia", e);
        }
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }
}
