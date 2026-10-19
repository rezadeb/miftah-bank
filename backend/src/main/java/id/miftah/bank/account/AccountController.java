package id.miftah.bank.account;

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

    /** Batas nominal per transfer (Rp 1 miliar) supaya saldo tidak overflow. */
    static final long MAX_AMOUNT = 1_000_000_000L;

    public record BalanceResponse(String username, String fullName, String accountNo, long balance, Instant asOf) {
    }

    public record TransferRequest(Long amount) {
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

    /** Cek Saldo -> baca dari PostgreSQL. */
    @GetMapping("/account/balance")
    public BalanceResponse balance(@RequestHeader(value = "Authorization", required = false) String auth) {
        String username = tokens.requireUser(auth);
        AccountRepository.Account account = accounts.findByUsername(username)
                .orElseThrow(() -> accountNotFound(username));
        return new BalanceResponse(account.username(), account.fullName(), account.accountNo(),
                account.balance(), Instant.now());
    }

    /** Transfer Uang -> saldo bertambah + riwayat tercatat di PostgreSQL. */
    @PostMapping("/transfer")
    public TransferResponse transfer(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody TransferRequest request) {
        String username = tokens.requireUser(auth);
        Long amount = request.amount();
        if (amount == null || amount < 1 || amount > MAX_AMOUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Nominal harus antara Rp 1 dan Rp 1.000.000.000");
        }
        AccountRepository.TransactionRecord record = accounts.addFundsAndRecord(username, amount)
                .orElseThrow(() -> accountNotFound(username));
        log.info("Transfer berhasil: user='{}' nominal={} saldoBaru={} trxId={}",
                username, amount, record.balanceAfter(), record.id());
        return new TransferResponse(record.id(), record.amount(), record.balanceAfter(),
                record.createdAt().toInstant());
    }

    /** Riwayat transaksi terbaru (tanggal, jam, jumlah). */
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

    private static ApiException accountNotFound(String username) {
        return new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                "Rekening untuk user '" + username + "' tidak ditemukan");
    }
}
