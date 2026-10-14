package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.CountingPasswordEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The concurrent verification cap on the real server (D-20261008-45), with the shipped value of 2 permits (the shared
 * test profile raises it to 8 so the burst ITs, which run up to 40 logins at once, count exact 401s). Two logins are
 * held inside the password comparison; a third attempt, from another address and with the CORRECT password, is
 * refused with 503 {@code SERVICE_BUSY} and {@code Retry-After: 1} without a hash and without a session. After the
 * holders finish the next attempt runs normally.
 */
@TestPropertySource(properties = "ceto.admin.login-rate-limit.max-concurrent-verifications=2")
class AdminLoginVerificationCapServerIT extends AbstractAdminServerIT {

    private static final String LOGIN = "/api/admin/login";
    private static final String WRONG = "not-the-password-of-the-owner";

    @Autowired
    private AdminProperties admin;

    private AdminClient bootstrapped(String address) throws Exception {
        AdminClient client = newClient(address);
        client.bootstrap();
        return client;
    }

    @Test
    void theContextRunsWithTwoPermitsLikeTheShippedDefault() {
        assertThat(admin.loginRateLimit().maxConcurrentVerifications()).isEqualTo(2);
    }

    @Test
    void withBothPermitsHeldTheNextAttemptGets503WithoutAHashOrASessionAndAfterTheReleaseItProceeds()
            throws Exception {
        AdminClient holderA = bootstrapped("203.0.114.1");
        AdminClient holderB = bootstrapped("203.0.114.2");
        AdminClient probe = bootstrapped("203.0.114.3");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (CountingPasswordEncoder.Hold hold = CountingPasswordEncoder.holdMatches()) {
            int before = CountingPasswordEncoder.matches();
            Future<AdminClient.Reply> a = pool.submit(() -> holderA.login(AdminTestCredentials.username(), WRONG));
            Future<AdminClient.Reply> b = pool.submit(() -> holderB.login(AdminTestCredentials.username(), WRONG));
            hold.awaitBlocked(2);

            AdminClient.Reply busy = probe.login(AdminTestCredentials.username(), AdminTestCredentials.password());

            assertThat(busy.status()).isEqualTo(503);
            assertThat(busy.header("retry-after")).isEqualTo("1");
            assertThat(busy.header("content-type")).startsWith("application/problem+json");
            ContractAssertions.assertMatchesGoldenForPath(busy.body(), busy.header("x-request-id"),
                    "admin-error-service-busy.json", LOGIN);
            assertThat(busy.setCookie("JSESSIONID")).as("no session for a refused attempt").isNull();
            assertThat(busy.header("cache-control")).isEqualTo("no-store");
            assertThat(busy.body()).doesNotContain(AdminTestCredentials.username());
            assertThat(CountingPasswordEncoder.matches() - before)
                    .as("only the two holders reached BCrypt; the refused attempt computed no hash").isEqualTo(2);

            hold.release();
            assertThat(a.get(10, TimeUnit.SECONDS).status()).isEqualTo(401);
            assertThat(b.get(10, TimeUnit.SECONDS).status()).isEqualTo(401);
            assertThat(probe.isAuthenticated()).isFalse();
        } finally {
            pool.shutdownNow();
        }

        AdminClient.Reply next = probe.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(next.status()).as("the permits are back").isEqualTo(204);
        assertThat(probe.isAuthenticated()).isTrue();
    }

    @Test
    void anOverlongFieldAndAnUnrelatedRequestAreNotAffectedWhileEveryPermitIsHeld() throws Exception {
        AdminClient holderA = bootstrapped("203.0.114.11");
        AdminClient holderB = bootstrapped("203.0.114.12");
        AdminClient other = bootstrapped("203.0.114.13");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (CountingPasswordEncoder.Hold hold = CountingPasswordEncoder.holdMatches()) {
            Future<AdminClient.Reply> a = pool.submit(() -> holderA.login(AdminTestCredentials.username(), WRONG));
            Future<AdminClient.Reply> b = pool.submit(() -> holderB.login(AdminTestCredentials.username(), WRONG));
            hold.awaitBlocked(2);

            AdminClient.Reply overlong = other.login(AdminTestCredentials.username(), "p".repeat(129));
            AdminClient.Reply session = other.send("GET", "/api/admin/session", null, null, java.util.Map.of());

            assertThat(overlong.status()).as("refused by the field cap, before the verification cap").isEqualTo(401);
            assertThat(session.status()).isEqualTo(200);
            hold.release();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
