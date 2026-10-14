package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.CountingPasswordEncoder;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The overflow bucket of the login limiter end to end (D-20261008-43 residual): with the client table tiny, new
 * addresses beyond it share ONE small bucket and get 429 once it is drained, while an address that already has its
 * own bucket keeps it. The dedicated context has 100 tracked clients (the minimum) and an overflow of 3 attempts, and
 * the clock never moves, so nothing refills. The 100 tracked clients are filled with token-less login POSTs (403),
 * which spend a token and cost no hash; this test must therefore be the only user of this context.
 */
@TestPropertySource(properties = {
    "ceto.admin.login-rate-limit.max-tracked-clients=100",
    "ceto.admin.login-rate-limit.overflow-burst=3",
    "ceto.admin.login-rate-limit.overflow-refill-per-hour=1"})
class AdminLoginOverflowServerIT extends AbstractAdminServerIT {

    @Autowired
    private AdminProperties admin;

    @Test
    void newAddressesBeyondTheTableShareTheOverflowBucketAndGet429OnceItIsDrained() throws Exception {
        assertThat(admin.loginRateLimit().maxTrackedClients()).isEqualTo(100);
        AdminClient tracked = newClient("203.0.116.1");
        tracked.bootstrap();
        for (int i = 1; i <= 100; i++) {
            AdminClient filler = newClient("203.0.116." + i);
            AdminClient.Reply reply = filler.loginWithHeaders(AdminTestCredentials.username(), "x", Map.of());
            assertThat(reply.status()).as("table filler %d spends its own token", i).isEqualTo(403);
        }
        int hashesBefore = CountingPasswordEncoder.matches();

        // Three NEW addresses beyond the table: the overflow bucket admits 3 attempts in total, whoever sends them.
        for (int i = 1; i <= 3; i++) {
            AdminClient fresh = newClient("203.0.117." + i);
            fresh.bootstrap();
            assertThat(fresh.login(AdminTestCredentials.username(), "not-the-password").status())
                    .as("overflow attempt %d", i).isEqualTo(401);
        }
        AdminClient fourthNewAddress = newClient("203.0.117.200");
        fourthNewAddress.bootstrap();
        AdminClient.Reply drained = fourthNewAddress.login(AdminTestCredentials.username(),
                AdminTestCredentials.password());

        assertThat(drained.status()).as("the shared overflow bucket is drained").isEqualTo(429);
        assertThat(drained.header("retry-after")).matches("[1-9][0-9]*");
        assertThat(CountingPasswordEncoder.matches() - hashesBefore)
                .as("hashes only for the three admitted overflow attempts").isEqualTo(3);
        assertThat(fourthNewAddress.isAuthenticated()).isFalse();

        // An address that has its own bucket is unaffected by the drained overflow: it still has 4 of its 5 tokens.
        AdminClient.Reply own = tracked.login(AdminTestCredentials.username(), AdminTestCredentials.password());
        assertThat(own.status()).as("a tracked address keeps its own bucket").isEqualTo(204);
    }
}
