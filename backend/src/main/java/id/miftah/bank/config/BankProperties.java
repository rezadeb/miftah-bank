package id.miftah.bank.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfigurasi aplikasi (prefix "bank" di application.yml).
 * Hanya untuk demo: password disimpan di config, bukan untuk production.
 */
@ConfigurationProperties(prefix = "bank")
public record BankProperties(
        String tokenSecret,
        int tokenTtlMinutes,
        long seedIntervalMs,
        List<SeedUser> seedUsers) {

    public record SeedUser(
            String username,
            String password,
            String fullName,
            String accountNo,
            long initialBalance) {
    }
}
