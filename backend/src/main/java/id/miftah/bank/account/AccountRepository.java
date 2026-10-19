package id.miftah.bank.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import id.miftah.bank.common.DependencyUnavailableException;
import id.miftah.bank.config.BankProperties.SeedUser;

/**
 * Akses PostgreSQL (saldo + riwayat transaksi) lewat JDBC.
 * Semua error koneksi/DB diubah jadi {@link DependencyUnavailableException} (HTTP 503 DB_UNAVAILABLE).
 */
@Repository
public class AccountRepository {

    public record Account(String username, String accountNo, String fullName, long balance) {
    }

    public record TransactionRecord(long id, String type, long amount, long balanceAfter, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public AccountRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Buat tabel kalau belum ada (idempotent). Dipanggil oleh SeedService. */
    public void ensureSchema() {
        run(() -> {
            jdbc.sql("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        username   VARCHAR(50)  PRIMARY KEY,
                        account_no VARCHAR(20)  NOT NULL UNIQUE,
                        full_name  VARCHAR(100) NOT NULL,
                        balance    BIGINT       NOT NULL DEFAULT 0
                    )""").update();
            jdbc.sql("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id            BIGSERIAL    PRIMARY KEY,
                        username      VARCHAR(50)  NOT NULL REFERENCES accounts(username),
                        type          VARCHAR(20)  NOT NULL DEFAULT 'TRANSFER',
                        amount        BIGINT       NOT NULL,
                        balance_after BIGINT       NOT NULL,
                        created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
                    )""").update();
            jdbc.sql("""
                    CREATE INDEX IF NOT EXISTS idx_transactions_user_time
                    ON transactions (username, created_at DESC)""").update();
            return null;
        });
    }

    /** Idempotent: insert akun awal kalau belum ada. Return true kalau baris baru dibuat. */
    public boolean seedIfMissing(SeedUser user) {
        return run(() -> jdbc.sql("""
                INSERT INTO accounts (username, account_no, full_name, balance)
                VALUES (:u, :no, :name, :bal)
                ON CONFLICT (username) DO NOTHING""")
                .param("u", user.username())
                .param("no", user.accountNo())
                .param("name", user.fullName())
                .param("bal", user.initialBalance())
                .update() > 0);
    }

    public Optional<Account> findByUsername(String username) {
        return run(() -> jdbc.sql("""
                SELECT username, account_no, full_name, balance
                FROM accounts WHERE username = :u""")
                .param("u", username)
                .query((rs, n) -> new Account(
                        rs.getString("username"),
                        rs.getString("account_no"),
                        rs.getString("full_name"),
                        rs.getLong("balance")))
                .optional());
    }

    /**
     * Transfer uang = saldo SELALU bertambah sebesar nominal, dan dicatat ke tabel riwayat
     * (tanggal + jam + jumlah) dalam satu transaksi database.
     */
    public Optional<TransactionRecord> addFundsAndRecord(String username, long amount) {
        return run(() -> tx.execute(status -> {
            Optional<Long> newBalance = jdbc.sql("""
                    UPDATE accounts SET balance = balance + :a
                    WHERE username = :u RETURNING balance""")
                    .param("a", amount)
                    .param("u", username)
                    .query(Long.class)
                    .optional();
            if (newBalance.isEmpty()) {
                return Optional.<TransactionRecord>empty();
            }
            TransactionRecord record = jdbc.sql("""
                    INSERT INTO transactions (username, type, amount, balance_after)
                    VALUES (:u, 'TRANSFER', :a, :b)
                    RETURNING id, type, amount, balance_after, created_at""")
                    .param("u", username)
                    .param("a", amount)
                    .param("b", newBalance.get())
                    .query(AccountRepository::mapTransaction)
                    .single();
            return Optional.of(record);
        }));
    }

    public List<TransactionRecord> findRecent(String username, int limit) {
        return run(() -> jdbc.sql("""
                SELECT id, type, amount, balance_after, created_at
                FROM transactions WHERE username = :u
                ORDER BY created_at DESC, id DESC LIMIT :l""")
                .param("u", username)
                .param("l", limit)
                .query(AccountRepository::mapTransaction)
                .list());
    }

    /** Cek koneksi database (untuk endpoint diagnostik). */
    public boolean ping() {
        try {
            return jdbc.sql("SELECT 1").query(Integer.class).single() == 1;
        } catch (DataAccessException | TransactionException e) {
            return false;
        }
    }

    private static TransactionRecord mapTransaction(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TransactionRecord(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getLong("amount"),
                rs.getLong("balance_after"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private <T> T run(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException | TransactionException e) {
            throw DependencyUnavailableException.database(e);
        }
    }
}
