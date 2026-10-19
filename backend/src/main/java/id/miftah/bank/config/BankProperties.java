package id.miftah.bank.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings (prefix "bank" in application.yml).
 * Demo only: demo passwords live in the config. Do not do this in production.
 */
@ConfigurationProperties(prefix = "bank")
public record BankProperties(
        String tokenSecret,
        int tokenTtlMinutes,
        long seedRetryMs,
        List<SeedUser> seedUsers) {

    public record SeedUser(
            String username,
            String password,
            String fullName,
            String accountNo,
            long initialBalance) {
    }
}
