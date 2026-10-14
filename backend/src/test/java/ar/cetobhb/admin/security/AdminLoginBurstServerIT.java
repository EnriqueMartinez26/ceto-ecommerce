package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.CountingPasswordEncoder;
import ar.cetobhb.support.MutableClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The login limiter on the real server (REQ-AUTH-10): a burst of wrong passwords from one address gets exactly the
 * burst allowance of 401s and 429s for the rest, with zero 5xx and BCrypt work only for the admitted attempts;
 * another address is never affected (the owner is not locked out); and every kind of attempt, including those that
 * would be refused later (no CSRF token, malformed), spends a token, so the limiter cannot be bypassed by sending
 * something that fails earlier. Addresses come from {@code X-Forwarded-For} (the test profile trusts 127.0.0.1), one
 * range per test so no two tests share a bucket.
 */
class AdminLoginBurstServerIT extends AbstractAdminServerIT {

    private static final String LOGIN = "/api/admin/login";
    private static final String WRONG = "not-the-password-of-the-owner";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @Autowired
    private AdminProperties admin;

    @Autowired
    private MutableClock clock;

    @AfterEach
    void resetTheClock() {
        clock.reset();
    }

    private int burst() {
        return admin.loginRateLimit().burst();
    }

    /** A client address of the documentation range 203.0.113.0/24 plus 198.51.100.0/24, unique per call. */
    private static String freshAddress() {
        int n = NEXT_ADDRESS.getAndIncrement();
        return "203.0." + (n / 250) + "." + (n % 250 + 1);
    }

    private AdminClient bootstrapped(String address) throws Exception {
        AdminClient client = newClient(address);
        client.bootstrap();
        return client;
    }

    private static AdminClient.Reply wrongPassword(AdminClient client) throws Exception {
        return client.login(AdminTestCredentials.username(), WRONG);
    }

    @Test
    void theShippedBurstIsFive() {
        assertThat(burst()).isEqualTo(5);
    }

    @Test
    void fortyConcurrentWrongPasswordsFromOneAddressGetTheBurstAs401AndTheRestAs429WithNoServerError()
            throws Exception {
        String address = freshAddress();
        List<AdminClient> clients = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            clients.add(bootstrapped(address));
        }
        int hashesBefore = CountingPasswordEncoder.matches();
        List<Callable<AdminClient.Reply>> attempts = new ArrayList<>();
        for (AdminClient client : clients) {
            attempts.add(() -> wrongPassword(client));
        }

        List<BurstRunner.Outcome<AdminClient.Reply>> outcomes = BurstRunner.run(attempts);

        Map<Integer, Integer> statuses = new TreeMap<>();
        for (BurstRunner.Outcome<AdminClient.Reply> outcome : outcomes) {
            assertThat(outcome.succeeded()).as("the HTTP exchange itself works: %s", outcome.error()).isTrue();
            statuses.merge(outcome.value().status(), 1, Integer::sum);
        }
        assertThat(statuses).as("status histogram").containsOnlyKeys(401, 429);
        assertThat(statuses.get(401)).as("admitted attempts are exactly the burst").isEqualTo(burst());
        assertThat(statuses.get(429)).isEqualTo(40 - burst());
        assertThat(CountingPasswordEncoder.matches() - hashesBefore)
                .as("BCrypt ran only for the admitted attempts").isEqualTo(burst());
        for (BurstRunner.Outcome<AdminClient.Reply> outcome : outcomes) {
            AdminClient.Reply reply = outcome.value();
            if (reply.status() == 429) {
                assertThat(reply.header("retry-after")).matches("[1-9][0-9]*");
                assertThat(reply.header("content-type")).startsWith("application/problem+json");
                ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                        "admin-error-rate-limited.json", LOGIN);
            }
        }
    }

    @Test
    void aLimitedAnswerCarriesTheAdminHardeningHeadersAndNoSessionOrCrossOriginHeader() throws Exception {
        AdminClient client = bootstrapped(freshAddress());
        for (int i = 0; i < burst(); i++) {
            assertThat(wrongPassword(client).status()).isEqualTo(401);
        }

        AdminClient.Reply limited = wrongPassword(client);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.header("cache-control")).isEqualTo("no-store");
        assertThat(limited.headers().get("x-content-type-options")).containsExactly("nosniff");
        assertThat(limited.header("content-security-policy")).isNotNull();
        assertThat(limited.header("referrer-policy")).isNotNull();
        assertThat(limited.headers()).doesNotContainKeys("access-control-allow-origin", "strict-transport-security");
        assertThat(limited.setCookie("JSESSIONID")).isNull();
        assertThat(limited.body()).doesNotContain(AdminTestCredentials.username()).doesNotContain(client.forwardedFor());
    }

    @Test
    void aLimitedAttemptComputesNoHashEvenWithTheCorrectPasswordAndOpensNoSession() throws Exception {
        AdminClient attacker = bootstrapped(freshAddress());
        for (int i = 0; i < burst(); i++) {
            assertThat(wrongPassword(attacker).status()).isEqualTo(401);
        }
        int hashesBefore = CountingPasswordEncoder.matches();

        for (int i = 0; i < 10; i++) {
            AdminClient.Reply reply = attacker.login(AdminTestCredentials.username(), AdminTestCredentials.password());

            assertThat(reply.status()).as("even the right password is refused while limited").isEqualTo(429);
            assertThat(reply.setCookie("JSESSIONID")).isNull();
        }

        assertThat(CountingPasswordEncoder.matches()).as("no hash for a limited attempt").isEqualTo(hashesBefore);
        assertThat(attacker.isAuthenticated()).isFalse();
    }

    @Test
    void anotherAddressIsNotAffectedSoTheOwnerCanStillLogIn() throws Exception {
        AdminClient attacker = bootstrapped(freshAddress());
        for (int i = 0; i < burst(); i++) {
            assertThat(wrongPassword(attacker).status()).isEqualTo(401);
        }
        assertThat(wrongPassword(attacker).status()).isEqualTo(429);

        AdminClient owner = bootstrapped(freshAddress());
        AdminClient.Reply reply = owner.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(reply.status()).isEqualTo(204);
        assertThat(owner.isAuthenticated()).isTrue();
    }

    @Test
    void aFloodWithoutACsrfTokenSpendsTokensTooSo403ThenTurnsInto429AndNoHashRuns() throws Exception {
        AdminClient flood = newClient(freshAddress());
        int hashesBefore = CountingPasswordEncoder.matches();

        for (int i = 0; i < burst(); i++) {
            AdminClient.Reply reply = flood.loginWithHeaders(AdminTestCredentials.username(), WRONG, Map.of());
            assertThat(reply.status()).as("attempt %d has no token", i + 1).isEqualTo(403);
        }
        AdminClient.Reply limited = flood.loginWithHeaders(AdminTestCredentials.username(), WRONG, Map.of());

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.header("retry-after")).matches("[1-9][0-9]*");
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(hashesBefore);
    }

    @Test
    void malformedLoginAttemptsSpendTokensToo() throws Exception {
        AdminClient client = bootstrapped(freshAddress());
        Map<String, String> csrf = client.csrfHeader();
        String urlencoded = "application/x-www-form-urlencoded";
        List<AdminClient.Reply> refused = List.of(
                client.send("POST", LOGIN + "?x=1", urlencoded, AdminClient.form("a", "b"), csrf),
                client.send("POST", LOGIN, "application/json", "{}", csrf),
                client.send("POST", LOGIN, urlencoded, "username=a&username=b&password=c", csrf),
                client.send("POST", LOGIN, urlencoded, "username=a", csrf),
                client.send("POST", LOGIN, urlencoded, "username=a&password=%zz", csrf));
        assertThat(refused).extracting(AdminClient.Reply::status).containsExactly(400, 415, 400, 400, 400);
        assertThat(refused).hasSize(burst());
        int hashesBefore = CountingPasswordEncoder.matches();

        AdminClient.Reply next = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(next.status()).as("the five malformed attempts used up the burst").isEqualTo(429);
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(hashesBefore);
    }

    @Test
    void spellingsThatAreNotTheLoginProcessingUrlInvokeNoHashAndSpendNoToken() throws Exception {
        AdminClient client = bootstrapped(freshAddress());
        Map<String, String> csrf = client.csrfHeader();
        String urlencoded = "application/x-www-form-urlencoded";
        String body = AdminClient.form(AdminTestCredentials.username(), AdminTestCredentials.password());
        int hashesBefore = CountingPasswordEncoder.matches();

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 2 * burst(); i++) {
            statuses.add(client.send("POST", LOGIN + ";x=1", urlencoded, body, csrf).status());
            statuses.add(client.send("POST", LOGIN + "/", urlencoded, body, csrf).status());
            statuses.add(client.send("POST", "/api/admin/Login", urlencoded, body, csrf).status());
            statuses.add(client.send("POST", "/API/admin/login", urlencoded, body, csrf).status());
        }

        assertThat(statuses).as("none of them is a login: 400 firewall, 401 anonymous, 404 unmapped")
                .allSatisfy(status -> assertThat(status).isIn(400, 401, 404));
        assertThat(client.send("POST", LOGIN + ";x=1", urlencoded, body, csrf).status()).isEqualTo(400);
        assertThat(CountingPasswordEncoder.matches()).as("no hash for a spelling that is not the login")
                .isEqualTo(hashesBefore);
        // The bucket is still full: the burst of real attempts is intact.
        for (int i = 0; i < burst(); i++) {
            assertThat(wrongPassword(client).status()).isEqualTo(401);
        }
        assertThat(wrongPassword(client).status()).isEqualTo(429);
    }

    /**
     * Decision: the body size filter runs before Security, as it does for checkout, so a body that DECLARES more than
     * {@code ceto.web.max-body-bytes} is refused with 413 without spending a token (it costs the server one header
     * check and no parsing). A chunked body is counted while it is read, after the limiter, so it does spend one.
     */
    @Test
    void anOversizedDeclaredBodyIsRefusedByTheBodyLimitBeforeTheLimiterAndSpendsNoToken() throws Exception {
        AdminClient client = bootstrapped(freshAddress());

        for (int i = 0; i < burst() + 3; i++) {
            assertThat(client.login(AdminTestCredentials.username(), "p".repeat(100_000)).status()).isEqualTo(413);
        }

        for (int i = 0; i < burst(); i++) {
            assertThat(wrongPassword(client).status()).isEqualTo(401);
        }
        assertThat(wrongPassword(client).status()).isEqualTo(429);
    }

    @Test
    void aTokenIsEarnedBackAfterTheRetryAfterThatTheAnswerAnnounced() throws Exception {
        AdminClient client = bootstrapped(freshAddress());
        for (int i = 0; i < burst(); i++) {
            assertThat(wrongPassword(client).status()).isEqualTo(401);
        }
        AdminClient.Reply limited = wrongPassword(client);
        assertThat(limited.status()).isEqualTo(429);
        long retryAfter = Long.parseLong(limited.header("retry-after"));
        assertThat(retryAfter).isEqualTo(admin.loginRateLimit().refillIntervalSeconds());

        clock.set(MutableClock.START.plusSeconds(retryAfter - 1));
        assertThat(wrongPassword(client).status()).as("one second early").isEqualTo(429);
        clock.set(MutableClock.START.plusSeconds(retryAfter));
        AdminClient.Reply admitted = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(admitted.status()).as("the owner gets in once the token is back").isEqualTo(204);
    }
}
