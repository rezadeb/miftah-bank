package id.miftah.bank.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Simple password hash (SHA-256). Fine for a demo lab. Not for production. */
final class Passwords {

    private Passwords() {
    }

    static String sha256Hex(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static boolean matches(String rawPassword, String storedHash) {
        return storedHash != null
                && MessageDigest.isEqual(
                        sha256Hex(rawPassword).getBytes(StandardCharsets.UTF_8),
                        storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
