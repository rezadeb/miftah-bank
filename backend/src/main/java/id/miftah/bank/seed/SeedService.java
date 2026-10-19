package id.miftah.bank.seed;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import id.miftah.bank.account.AccountRepository;
import id.miftah.bank.auth.RedisUserStore;
import id.miftah.bank.common.DependencyUnavailableException;
import id.miftah.bank.config.BankProperties;
import id.miftah.bank.config.BankProperties.SeedUser;

/**
 * Mengisi data awal (user demo) ke Redis dan PostgreSQL.
 *
 * - Idempotent: data yang sudah ada tidak ditimpa (saldo tidak di-reset).
 * - Tetap mencoba tiap beberapa detik, jadi backend boleh start lebih dulu daripada
 *   Redis/Postgres, dan data otomatis terisi lagi kalau pod database dibuat ulang tanpa volume.
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private final BankProperties props;
    private final RedisUserStore redisUsers;
    private final AccountRepository accounts;

    // Supaya log tidak "banjir" saat dependency mati: log sekali per outage.
    private final AtomicBoolean redisWarned = new AtomicBoolean(false);
    private final AtomicBoolean dbWarned = new AtomicBoolean(false);

    public SeedService(BankProperties props, RedisUserStore redisUsers, AccountRepository accounts) {
        this.props = props;
        this.redisUsers = redisUsers;
        this.accounts = accounts;
    }

    @Scheduled(initialDelay = 1000, fixedDelayString = "${bank.seed-interval-ms}")
    public void seed() {
        seedRedis();
        seedDatabase();
    }

    private void seedRedis() {
        try {
            int created = 0;
            for (SeedUser u : props.seedUsers()) {
                if (redisUsers.seedIfMissing(u)) {
                    created++;
                }
            }
            if (created > 0) {
                log.info("Seed Redis: {} user ditambahkan", created);
            }
            redisWarned.set(false);
        } catch (DependencyUnavailableException e) {
            if (redisWarned.compareAndSet(false, true)) {
                log.warn("Seed Redis ditunda: Redis belum tersedia, akan dicoba lagi");
            }
        }
    }

    private void seedDatabase() {
        try {
            accounts.ensureSchema();
            int created = 0;
            for (SeedUser u : props.seedUsers()) {
                if (accounts.seedIfMissing(u)) {
                    created++;
                }
            }
            if (created > 0) {
                log.info("Seed PostgreSQL: {} rekening ditambahkan", created);
            }
            dbWarned.set(false);
        } catch (DependencyUnavailableException e) {
            if (dbWarned.compareAndSet(false, true)) {
                log.warn("Seed PostgreSQL ditunda: database belum tersedia, akan dicoba lagi");
            }
        }
    }
}
