package id.miftah.bank;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import id.miftah.bank.account.AccountController;
import id.miftah.bank.account.AccountRepository;
import id.miftah.bank.account.AccountRepository.Account;
import id.miftah.bank.account.AccountRepository.TransactionRecord;
import id.miftah.bank.auth.AuthController;
import id.miftah.bank.auth.RedisUserStore;
import id.miftah.bank.auth.TokenService;
import id.miftah.bank.common.DependencyUnavailableException;
import id.miftah.bank.common.GlobalExceptionHandler;
import id.miftah.bank.config.BankProperties;

/**
 * Tests the HTTP behavior of the real controllers. Redis and PostgreSQL are replaced by mocks.
 * The main goal: every failure gives the RIGHT status code and error code,
 * because these codes are what we look at in Dynatrace.
 */
class ApiBehaviorTest {

    // SHA-256 of "demo123"
    private static final String DEMO_HASH = "d3ad9315b7be5dd53b31a273b3b3aba5defe700808305aa16a3062b76658a791";

    private RedisUserStore redis;
    private AccountRepository accounts;
    private TokenService tokens;
    private MockMvc mvc;
    private String bearer;

    @BeforeEach
    void setUp() {
        redis = mock(RedisUserStore.class);
        accounts = mock(AccountRepository.class);
        tokens = new TokenService(new BankProperties("test-secret-at-least-32-characters-long!", 60, 5000, List.of()));
        mvc = MockMvcBuilders
                .standaloneSetup(new AuthController(redis, tokens), new AccountController(accounts, tokens))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        bearer = "Bearer " + tokens.issue("demo");
    }

    // ------------------------------------------------------------------ login (Redis)

    @Test
    void loginWithRightPasswordReturnsToken() throws Exception {
        when(redis.find("demo")).thenReturn(Optional.of(
                new RedisUserStore.StoredUser("demo", DEMO_HASH, "Demo User", "1000000001")));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.accountNo").value("1000000001"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        when(redis.find("demo")).thenReturn(Optional.of(
                new RedisUserStore.StoredUser("demo", DEMO_HASH, "Demo User", "1000000001")));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginWhenRedisIsDownReturns503() throws Exception {
        when(redis.find("demo")).thenThrow(DependencyUnavailableException.redis(new RuntimeException("down")));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REDIS_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ balance and transfer (PostgreSQL)

    @Test
    void balanceWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/account/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void balanceReturnsAmountFromDatabase() throws Exception {
        when(accounts.findByUsername("demo"))
                .thenReturn(Optional.of(new Account("demo", "1000000001", "Demo User", 1_350_000)));

        mvc.perform(get("/api/account/balance").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1_350_000));
    }

    @Test
    void balanceWhenDatabaseIsDownReturns503() throws Exception {
        when(accounts.findByUsername("demo"))
                .thenThrow(DependencyUnavailableException.database(new RuntimeException("down")));

        mvc.perform(get("/api/account/balance").header("Authorization", bearer))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }

    @Test
    void transferAddsMoneyAndReturnsNewBalance() throws Exception {
        when(accounts.addFundsAndRecord("demo", 250_000)).thenReturn(Optional.of(
                new TransactionRecord(7, "TRANSFER", 250_000, 1_250_000, OffsetDateTime.now())));

        mvc.perform(post("/api/transfer").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":250000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(7))
                .andExpect(jsonPath("$.balance").value(1_250_000));
    }

    @Test
    void transferAcceptsWholeNumberWrittenWithZeroDecimals() throws Exception {
        when(accounts.addFundsAndRecord("demo", 1000)).thenReturn(Optional.of(
                new TransactionRecord(8, "TRANSFER", 1000, 2000, OffsetDateTime.now())));

        mvc.perform(post("/api/transfer").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000.0}"))
                .andExpect(status().isOk());
    }

    @Test
    void transferRejectsBadAmountsAndNeverTouchesTheDatabase() throws Exception {
        for (String body : List.of(
                "{\"amount\":0}", "{\"amount\":-5}", "{\"amount\":1.5}",
                "{\"amount\":1000000001}", "{}", "{\"amount\":null}")) {
            mvc.perform(post("/api/transfer").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        }
        verify(accounts, never()).addFundsAndRecord(eq("demo"), anyLong());
    }

    @Test
    void transferWhenDatabaseIsDownReturns503() throws Exception {
        when(accounts.addFundsAndRecord("demo", 1000))
                .thenThrow(DependencyUnavailableException.database(new RuntimeException("down")));

        mvc.perform(post("/api/transfer").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ errors from Spring itself must NOT become 500

    @Test
    void wrongHttpMethodReturns405NotAnInternalError() throws Exception {
        mvc.perform(get("/api/transfer"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void invalidQueryParameterReturns400() throws Exception {
        mvc.perform(get("/api/transactions").param("limit", "abc").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/transfer").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
