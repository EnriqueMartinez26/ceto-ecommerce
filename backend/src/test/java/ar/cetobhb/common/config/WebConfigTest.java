package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.TokenBucketLimiter;
import ar.cetobhb.support.MutableClock;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** The remote IP valve settings that Tomcat really uses are checked at startup, whatever overrode them. */
class WebConfigTest {

    private static final WebProperties WEB = new WebProperties(200, "10.0.0.0/8",
            new WebProperties.CheckoutRateLimit(10, 360, 10_000, 100, 100));

    private static MockEnvironment environment(String proxies, String strategy) {
        MockEnvironment environment = new MockEnvironment();
        if (proxies != null) {
            environment.setProperty("server.tomcat.remoteip.internal-proxies", proxies);
        }
        if (strategy != null) {
            environment.setProperty("server.forward-headers-strategy", strategy);
        }
        return environment;
    }

    @Test
    void theLimiterTakesItsOverflowBudgetFromTheSettingsNotFromTheClientOne() {
        WebProperties.CheckoutRateLimit limit = new WebProperties.CheckoutRateLimit(2, 360, 100, 3, 100);
        TokenBucketLimiter limiter = WebConfig.checkoutLimiter(new MutableClock(), limit);
        for (int client = 0; client < 100; client++) {
            assertThat(limiter.tryAcquire("tracked-" + client).allowed()).isTrue();
        }

        for (int visitor = 1; visitor <= 3; visitor++) {
            assertThat(limiter.tryAcquire("new-" + visitor).allowed()).as("overflow burst of 3, visitor %d", visitor)
                    .isTrue();
        }
        TokenBucketLimiter.Decision rejected = limiter.tryAcquire("new-4");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).as("100 per hour = one token every 36 s").isEqualTo(36);
        assertThat(limiter.tryAcquire("tracked-0").retryAfterSeconds()).as("a tracked client keeps its own pace")
                .isEqualTo(0);
    }

    @Test
    void acceptsPrivateProxiesWithTheNativeStrategy() {
        assertThat(new WebConfig(Clock.systemUTC(), WEB, environment("10.0.0.0/8", "native"))).isNotNull();
        assertThat(new WebConfig(Clock.systemUTC(), WEB, environment("172[.]18[.].*", "NATIVE"))).isNotNull();
    }

    @ParameterizedTest(name = "internal-proxies [{0}] stops the startup")
    @ValueSource(strings = {"", "0.0.0.0/0", ".*"})
    void rejectsAnEffectiveProxyValueThatTrustsEveryone(String proxies) {
        assertThatThrownBy(() -> new WebConfig(Clock.systemUTC(), WEB, environment(proxies, "native")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("internal-proxies");
    }

    @Test
    void rejectsAMissingProxyValue() {
        // Unset means Tomcat's default: every private and loopback peer is trusted.
        assertThatThrownBy(() -> new WebConfig(Clock.systemUTC(), WEB, environment(null, "native")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("internal-proxies");
    }

    @ParameterizedTest(name = "strategy [{0}] stops the startup")
    @ValueSource(strings = {"framework", "none", ""})
    void rejectsAnyStrategyOtherThanNative(String strategy) {
        assertThatThrownBy(() -> new WebConfig(Clock.systemUTC(), WEB, environment("10.0.0.0/8", strategy)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forward-headers-strategy");
    }

    @Test
    void rejectsAMissingStrategy() {
        assertThatThrownBy(() -> new WebConfig(Clock.systemUTC(), WEB, environment("10.0.0.0/8", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forward-headers-strategy");
    }
}
