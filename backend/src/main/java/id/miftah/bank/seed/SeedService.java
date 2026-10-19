package id.miftah.bank.seed;

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
 * Loads the demo users into Redis and the demo accounts into PostgreSQL.
 *
 * <ul>
 *   <li>Safe to run many times: existing data is never overwritten, so balances are not reset.</li>
 *   <li>The backend may start before Redis or PostgreSQL. It retries every few seconds and
 *       stops as soon as that dependency is seeded. After that it does no background work,
 *       so no extra Redis or database calls show up in Dynatrace.</li>
 *   <li>If you delete the Redis or PostgreSQL volume, restart the backend to seed again.</li>
 * </ul>
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private final BankProperties props;
    private final RedisUserStore redisUsers;
    private final AccountRepository accounts;

    // Only the scheduler thread touches these flags. 'volatile' is enough.
    private volatile boolean redisSeeded;
    private volatile boolean databaseSeeded;
    private volatile boolean redisWarned;
    private volatile boolean databaseWarned;

    public SeedService(BankProperties props, RedisUserStore redisUsers, AccountRepository accounts) {
        this.props = props;
        this.redisUsers = redisUsers;
        this.accounts = accounts;
    }

    @Scheduled(initialDelay = 1000, fixedDelayString = "${bank.seed-retry-ms}")
    public void seed() {
        if (!redisSeeded) {
            seedRedis();
        }
        if (!databaseSeeded) {
            seedDatabase();
        }
    }

    private void seedRedis() {
        try {
            int created = 0;
            for (SeedUser user : props.seedUsers()) {
                if (redisUsers.seedIfMissing(user)) {
                    created++;
                }
            }
            redisSeeded = true;
            log.info("Redis seed done: {} new user(s) added", created);
        } catch (DependencyUnavailableException e) {
            if (!redisWarned) {
                redisWarned = true;
                log.warn("Redis is not ready yet, will retry the seed");
            }
        }
    }

    private void seedDatabase() {
        try {
            accounts.ensureSchema();
            int created = 0;
            for (SeedUser user : props.seedUsers()) {
                if (accounts.seedIfMissing(user)) {
                    created++;
                }
            }
            databaseSeeded = true;
            log.info("PostgreSQL seed done: {} new account(s) added", created);
        } catch (DependencyUnavailableException e) {
            if (!databaseWarned) {
                databaseWarned = true;
                log.warn("PostgreSQL is not ready yet, will retry the seed");
            }
        }
    }
}
