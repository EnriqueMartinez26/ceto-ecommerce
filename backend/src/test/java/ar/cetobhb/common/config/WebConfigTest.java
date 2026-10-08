package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.TokenBucketLimiter;
import ar.cetobhb.support.MutableClock;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** The remote IP valve settings that Tomcat really uses are checked at startup, whatever overrode them. */
class WebConfigTest {

    private static final WebProperties WEB = new WebProperties(200, "10.0.0.0/8",
            new WebProperties.CheckoutRateLimit(10, 360, 10_000, 100, 100));

    private static final String PROXIES = "server.tomcat.remoteip.internal-proxies";
    private static final String STRATEGY = "server.forward-headers-strategy";
    private static final String HEADER = "server.tomcat.remoteip.remote-ip-header";
    private static final String TRUSTED = "server.tomcat.remoteip.trusted-proxies";
    private static final String PROXIES_HEADER = "server.tomcat.remoteip.proxies-header";

    private static MockEnvironment environment(String proxies, String strategy) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(HEADER, "X-Forwarded-For");
        if (proxies != null) {
            environment.setProperty(PROXIES, proxies);
        }
        if (strategy != null) {
            environment.setProperty(STRATEGY, strategy);
        }
        return environment;
    }

    /** The effective settings of a correct deployment (what application.yml wires). */
    private static MockEnvironment valid() {
        return environment("10.0.0.0/8", "native");
    }

    private static WebConfig configWith(MockEnvironment environment) {
        return new WebConfig(Clock.systemUTC(), WEB, environment);
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
        assertThat(configWith(valid())).isNotNull();
        MockEnvironment upperCase = valid();
        upperCase.setProperty(PROXIES, "172.18.0.0/16, 127.0.0.1/32");
        upperCase.setProperty(STRATEGY, "NATIVE");
        upperCase.setProperty(HEADER, "x-forwarded-for");
        assertThat(configWith(upperCase)).isNotNull();
    }

    @Test
    void acceptsBlankTrustedAndProxiesHeaderSettings() {
        MockEnvironment blank = valid();
        blank.setProperty(TRUSTED, "");
        blank.setProperty(PROXIES_HEADER, "  ");

        assertThat(configWith(blank)).isNotNull();
    }

    @ParameterizedTest(name = "internal-proxies [{0}] stops the startup")
    @ValueSource(strings = {"", "0.0.0.0/0", ".*", "172.0.0.0/8", "192.0.0.0/8", "2400::/12", "127\\.0\\.0\\.1",
        "10.0.0.0/8,"})
    void rejectsAnEffectiveProxyValueThatTrustsEveryone(String proxies) {
        assertThatThrownBy(() -> configWith(environment(proxies, "native")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("internal-proxies");
    }

    @Test
    void rejectsAMissingProxyValue() {
        // Unset means Tomcat's default: every private and loopback peer is trusted.
        assertThatThrownBy(() -> configWith(environment(null, "native")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("internal-proxies");
    }

    @ParameterizedTest(name = "strategy [{0}] stops the startup")
    @ValueSource(strings = {"framework", "none", ""})
    void rejectsAnyStrategyOtherThanNative(String strategy) {
        assertThatThrownBy(() -> configWith(environment("10.0.0.0/8", strategy)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forward-headers-strategy");
    }

    @Test
    void rejectsAMissingStrategy() {
        assertThatThrownBy(() -> configWith(environment("10.0.0.0/8", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forward-headers-strategy");
    }

    @ParameterizedTest(name = "remote-ip-header [{0}] stops the startup")
    @ValueSource(strings = {"X-Real-IP", "Forwarded", "X-Forwarded-For2", "", " "})
    void rejectsAnyHeaderOtherThanXForwardedFor(String header) {
        MockEnvironment environment = valid();
        environment.setProperty(HEADER, header);

        assertThatThrownBy(() -> configWith(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remote-ip-header");
    }

    @Test
    void rejectsAMissingRemoteIpHeader() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(PROXIES, "10.0.0.0/8");
        environment.setProperty(STRATEGY, "native");

        assertThatThrownBy(() -> configWith(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remote-ip-header");
    }

    @ParameterizedTest(name = "trusted-proxies [{0}] stops the startup")
    @ValueSource(strings = {".*", "10.0.0.0/8", "127\\.0\\.0\\.1", "0.0.0.0/0"})
    void rejectsAnyTrustedProxiesSetting(String trusted) {
        // The valve skips every trusted proxy found in the chain: even a private one is more trust than REQ-ABU-03 wants.
        MockEnvironment environment = valid();
        environment.setProperty(TRUSTED, trusted);

        assertThatThrownBy(() -> configWith(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxies");
    }

    @Test
    void rejectsAProxiesHeaderSetting() {
        MockEnvironment environment = valid();
        environment.setProperty(PROXIES_HEADER, "X-Forwarded-By");

        assertThatThrownBy(() -> configWith(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proxies-header");
    }

    @Test
    void anEnvironmentVariableOverrideOfTheTrustedProxiesStopsTheStartup() {
        // SERVER_TOMCAT_REMOTEIP_TRUSTEDPROXIES has no hyphen: only Boot's relaxed binding maps it to trusted-proxies.
        StandardEnvironment environment = withEnvironmentVariables(Map.of(
                "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES", "10.0.0.0/8", "SERVER_FORWARDHEADERSSTRATEGY", "native",
                "SERVER_TOMCAT_REMOTEIP_REMOTEIPHEADER", "X-Forwarded-For",
                "SERVER_TOMCAT_REMOTEIP_TRUSTEDPROXIES", ".*"));

        assertThatThrownBy(() -> new WebConfig(Clock.systemUTC(), WEB, environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trusted-proxies");
    }

    @Test
    void anEnvironmentVariableOverrideOfTheHeaderStopsTheStartup() {
        StandardEnvironment environment = withEnvironmentVariables(Map.of(
                "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES", "10.0.0.0/8", "SERVER_FORWARDHEADERSSTRATEGY", "native",
                "SERVER_TOMCAT_REMOTEIP_REMOTEIPHEADER", "X-Real-IP"));

        assertThatThrownBy(() -> new WebConfig(Clock.systemUTC(), WEB, environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("remote-ip-header");
    }

    @Test
    void anEnvironmentVariableOverrideOfTheInternalProxiesStopsTheStartup() {
        StandardEnvironment environment = withEnvironmentVariables(Map.of(
                "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES", ".*", "SERVER_FORWARDHEADERSSTRATEGY", "native",
                "SERVER_TOMCAT_REMOTEIP_REMOTEIPHEADER", "X-Forwarded-For"));

        assertThatThrownBy(() -> new WebConfig(Clock.systemUTC(), WEB, environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("internal-proxies");
    }

    @Test
    void correctEnvironmentVariablesStartUp() {
        StandardEnvironment environment = withEnvironmentVariables(Map.of(
                "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES", "10.0.0.0/8", "SERVER_FORWARDHEADERSSTRATEGY", "native",
                "SERVER_TOMCAT_REMOTEIP_REMOTEIPHEADER", "X-Forwarded-For"));

        assertThat(new WebConfig(Clock.systemUTC(), WEB, environment)).isNotNull();
    }

    /** An environment whose only source is the given variables, bound the way Boot binds the real ones. */
    private static StandardEnvironment withEnvironmentVariables(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        ConfigurationPropertySources.attach(environment);
        return environment;
    }
}
