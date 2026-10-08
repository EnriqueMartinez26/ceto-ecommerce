package ar.cetobhb.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractServerIT;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Client address resolution on a real server (REQ-ABU-03, verification U-6): Tomcat's remote IP valve trusts only
 * 127.0.0.1/32 in the test profile, and the limiter keys on whatever {@code getRemoteAddr()} returns afterwards.
 * Each test spends the burst of ONE client key with unreadable bodies (400 = passed the limiter, 429 = stopped by it)
 * and then looks at which other requests share that key. Addresses come from the documentation ranges, one per test.
 */
class ClientIpHttpIT extends AbstractServerIT {

    private static final int BURST = 10;
    private static final String LOOPBACK = "127.0.0.1";
    private static final String UNTRUSTED_PEER = "127.0.0.2";

    @Autowired
    private ApplicationContext context;

    /**
     * The native strategy must not be accompanied by Spring's ForwardedHeaderFilter (the "framework" strategy), which
     * would believe X-Forwarded-Host and friends from any caller.
     */
    @Test
    void noForwardedHeaderFilterIsRegistered() {
        assertThat(context.getBeansOfType(ForwardedHeaderFilter.class)).isEmpty();
        assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                .noneMatch(registration -> registration.getFilter() instanceof ForwardedHeaderFilter);
    }

    private int attempt(String host, String forwardedFor) throws Exception {
        return postFrom(host, forwardedFor, "key-" + System.nanoTime(), "{").status();
    }

    private void spendBurst(String host, IntFunction<String> forwardedFor) throws Exception {
        for (int i = 0; i < BURST; i++) {
            assertThat(attempt(host, forwardedFor.apply(i))).as("attempt %d", i + 1).isEqualTo(400);
        }
    }

    @Test
    void theForwardedAddressOfATrustedProxyIsTheBucketKey() throws Exception {
        spendBurst(LOOPBACK, i -> "203.0.113.7");

        assertThat(attempt(LOOPBACK, "203.0.113.7")).isEqualTo(429);
        assertThat(attempt(LOOPBACK, "203.0.113.8")).isEqualTo(400);
    }

    @Test
    void aProxyWithoutTheHeaderIsItsOwnClient() throws Exception {
        spendBurst(LOOPBACK, i -> null);

        assertThat(attempt(LOOPBACK, null)).isEqualTo(429);
        assertThat(attempt(LOOPBACK, "203.0.113.9")).isEqualTo(400);
    }

    @Test
    void aForgedLeftmostEntryDoesNotBuyAFreshBucket() throws Exception {
        // The trusted proxy appends the real client (203.0.113.10) to whatever the caller sent: the valve walks the
        // list from the right and stops at the first address that is not a trusted proxy.
        spendBurst(LOOPBACK, i -> "1.2.3." + (i + 1) + ", 203.0.113.10");

        assertThat(attempt(LOOPBACK, "9.9.9.9, 203.0.113.10")).isEqualTo(429);
        assertThat(attempt(LOOPBACK, "9.9.9.9, 203.0.113.11")).isEqualTo(400);
    }

    @Test
    void aTrustedProxyInTheChainIsSkippedToReachTheClient() throws Exception {
        spendBurst(LOOPBACK, i -> "203.0.113.12, 127.0.0.1");

        assertThat(attempt(LOOPBACK, "203.0.113.12")).isEqualTo(429);
    }

    @Test
    void anUnreadableForwardedValueSharesOneBucketInsteadOfBuyingFreshOnes() throws Exception {
        // The valve takes the entry verbatim as the remote address; ClientIpKey maps every non-address to one key.
        spendBurst(LOOPBACK, i -> "not-an-ip-" + i);

        assertThat(attempt(LOOPBACK, "yet-another-garbage")).isEqualTo(429);
        assertThat(attempt(LOOPBACK, "203.0.113.13")).isEqualTo(400);
    }

    @Test
    void theHeaderOfAnUntrustedPeerIsIgnored() throws Exception {
        // Only 127.0.0.1/32 is trusted. This peer connects from 127.0.0.2: loopback too, but not a trusted proxy, so
        // every forwarded value is just a header. No IPv6 or other optional network feature is involved.
        for (int i = 0; i < BURST; i++) {
            assertThat(untrustedAttempt("203.0.113." + (100 + i))).as("attempt %d", i + 1).isEqualTo(400);
        }

        assertThat(untrustedAttempt("203.0.113.200")).isEqualTo(429);
        assertThat(untrustedAttempt(null)).isEqualTo(429);
        assertThat(attempt(LOOPBACK, "203.0.113.100")).as("the forwarded value never became a key").isEqualTo(400);
    }

    private int untrustedAttempt(String forwardedFor) throws Exception {
        return postBoundTo(UNTRUSTED_PEER, forwardedFor, "key-" + System.nanoTime(), "{").status();
    }
}
