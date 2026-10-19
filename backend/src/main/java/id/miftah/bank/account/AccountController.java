package id.miftah.bank.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import id.miftah.bank.auth.TokenService;
import id.miftah.bank.common.ApiException;

@RestController
@RequestMapping("/api")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    /** Maximum amount per transfer (Rp 1 billion), so the balance can never overflow. */
    static final BigDecimal MAX_AMOUNT = BigDecimal.valueOf(1_000_000_000L);

    public record BalanceResponse(String username, String fullName, String accountNo, long balance, Instant asOf) {
    }

    /** The amount is read as a BigDecimal so we can reject decimals like 1.5 instead of cutting them to 1. */
    public record TransferRequest(BigDecimal amount) {
    }

    public record TransferResponse(long transactionId, long amount, long balance, Instant createdAt) {
    }

    public record TransactionItem(long id, String type, long amount, long balanceAfter, Instant createdAt) {
    }

    private final AccountRepository accounts;
    private final TokenService tokens;

    public AccountController(AccountRepository accounts, TokenService tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    /** Check balance: reads from PostgreSQL. */
    @GetMapping("/account/balance")
    public BalanceResponse balance(@RequestHeader(value = "Authorization", required = false) String auth) {
        String username = tokens.requireUser(auth);
        AccountRepository.Account account = accounts.findByUsername(username)
                .orElseThrow(() -> accountNotFound(username));
        return new BalanceResponse(account.username(), account.fullName(), account.accountNo(),
                account.balance(), Instant.now());
    }

    /** Transfer money: the balance goes UP by the amount, and a history row is saved in PostgreSQL. */
    @PostMapping("/transfer")
    public TransferResponse transfer(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody TransferRequest request) {
        String username = tokens.requireUser(auth);
        long amount = parseAmount(request.amount());

        AccountRepository.TransactionRecord record = accounts.addFundsAndRecord(username, amount)
                .orElseThrow(() -> accountNotFound(username));
        log.info("Transfer successful: user='{}' amount={} newBalance={} transactionId={}",
                username, amount, record.balanceAfter(), record.id());
        return new TransferResponse(record.id(), record.amount(), record.balanceAfter(),
                record.createdAt().toInstant());
    }

    /** Latest transactions (date, time, amount). */
    @GetMapping("/transactions")
    public List<TransactionItem> transactions(@RequestHeader(value = "Authorization", required = false) String auth,
                                              @RequestParam(defaultValue = "20") int limit) {
        String username = tokens.requireUser(auth);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return accounts.findRecent(username, safeLimit).stream()
                .map(t -> new TransactionItem(t.id(), t.type(), t.amount(), t.balanceAfter(),
                        t.createdAt().toInstant()))
                .toList();
    }

    /** The amount must be a whole number from 1 to MAX_AMOUNT. */
    private static long parseAmount(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.compareTo(MAX_AMOUNT) > 0
                || value.stripTrailingZeros().scale() > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Amount must be a whole number from Rp 1 to Rp 1,000,000,000");
        }
        return value.longValueExact();
    }

    private static ApiException accountNotFound(String username) {
        return new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                "No account found for user '" + username + "'");
    }
}
