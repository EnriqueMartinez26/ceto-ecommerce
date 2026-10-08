package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-closed web settings (REQ-HRD-07, REQ-ABU-03): out-of-range values and an unsafe or missing trusted-proxies
 * value stop the startup. The runner loads the real application.yml, so the shipped defaults are what is under test.
 */
class WebPropertiesValidationTest {

    private static final String PROXIES = "ceto.web.trusted-proxies";
    private static final String SAFE_CIDR = "10.0.0.0/8";

    @Configuration
    @EnableConfigurationProperties(WebProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues(PROXIES + "=" + SAFE_CIDR);

    private static String trace(Throwable failure) {
        java.io.StringWriter out = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(out));
        return out.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    @Test
    void appliesTheDocumentedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            WebProperties p = context.getBean(WebProperties.class);
            assertThat(p.storefrontMaxProducts()).isEqualTo(200);
            assertThat(p.checkoutRateLimit().burst()).isEqualTo(10);
            assertThat(p.checkoutRateLimit().refillIntervalSeconds()).isEqualTo(360);
            assertThat(p.checkoutRateLimit().maxTrackedClients()).isEqualTo(10_000);
            assertThat(p.checkoutRateLimit().overflowBurst()).isEqualTo(100);
            assertThat(p.checkoutRateLimit().overflowRefillPerHour()).isEqualTo(100);
            assertThat(p.checkoutRateLimit().overflowRefillInterval()).isEqualTo(java.time.Duration.ofSeconds(36));
            assertThat(p.trustedProxies()).isEqualTo(SAFE_CIDR);
        });
    }

    @Test
    void startupFailsWhenTrustedProxiesIsNotConfigured() {
        // No value anywhere: the ${TRUSTED_PROXIES} placeholder of application.yml has no default, by design.
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(trace(context.getStartupFailure())).contains("trustedproxies");
                });
    }

    @ParameterizedTest(name = "trusted-proxies [{0}] fails startup")
    @ValueSource(strings = {"", " ", "0.0.0.0/0", "::/0", "128.0.0.0/1", "172.0.0.0/8", "192.0.0.0/8", "2400::/12",
        "::ffff:0:0/96", ".*", ".+", "\\d+\\.\\d+\\.\\d+\\.\\d+", "127\\.0\\.0\\.1", "10.0.0.0/8,0.0.0.0/0",
        "10.0.0.0/8,", "10.0.0.0/33", "not/a-cidr", "(unclosed", "127\\.0\\.0\\.1|.*"})
    void startupFailsForAProxyValueThatIsNotAPrivateCidrList(String value) {
        runner.withPropertyValues(PROXIES + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(trace(context.getStartupFailure())).contains("trustedproxies");
        });
    }

    @ParameterizedTest(name = "trusted-proxies [{0}] is accepted")
    @ValueSource(strings = {"10.0.0.0/8", "172.16.0.0/12, 192.168.0.0/16", "fc00::/7", "127.0.0.1/32,::1/128"})
    void startupAcceptsPrivateCidrLists(String value) {
        runner.withPropertyValues(PROXIES + "=" + value).run(context -> assertThat(context).hasNotFailed());
    }

    static Stream<Arguments> outOfRangeValues() {
        return Stream.of(
                Arguments.of("ceto.web.storefront-max-products", "0"),
                Arguments.of("ceto.web.storefront-max-products", "501"),
                Arguments.of("ceto.web.checkout-rate-limit.burst", "0"),
                Arguments.of("ceto.web.checkout-rate-limit.burst", "101"),
                Arguments.of("ceto.web.checkout-rate-limit.refill-interval-seconds", "0"),
                Arguments.of("ceto.web.checkout-rate-limit.refill-interval-seconds", "86401"),
                Arguments.of("ceto.web.checkout-rate-limit.max-tracked-clients", "99"),
                Arguments.of("ceto.web.checkout-rate-limit.max-tracked-clients", "100001"),
                Arguments.of("ceto.web.checkout-rate-limit.overflow-burst", "0"),
                Arguments.of("ceto.web.checkout-rate-limit.overflow-burst", "10001"),
                Arguments.of("ceto.web.checkout-rate-limit.overflow-refill-per-hour", "0"),
                Arguments.of("ceto.web.checkout-rate-limit.overflow-refill-per-hour", "3601"));
    }

    @ParameterizedTest(name = "{0}={1} fails startup")
    @MethodSource("outOfRangeValues")
    void startupFailsForOutOfRangeValues(String key, String value) {
        runner.withPropertyValues(key + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(trace(context.getStartupFailure())).contains(key.replace("-", "").toLowerCase(Locale.ROOT));
        });
    }

    static Stream<Arguments> boundaryValues() {
        return Stream.of(
                Arguments.of("ceto.web.storefront-max-products", "1"),
                Arguments.of("ceto.web.storefront-max-products", "500"),
                Arguments.of("ceto.web.checkout-rate-limit.burst", "1"),
                Arguments.of("ceto.web.checkout-rate-limit.burst", "100"),
                Arguments.of("ceto.web.checkout-rate-limit.refill-interval-seconds", "1"),
                Arguments.of("ceto.web.checkout-rate-limit.refill-interval-seconds", "86400"),
                Arguments.of("ceto.web.checkout-rate-limit.max-tracked-clients", "100"),
                Arguments.of("ceto.web.checkout-rate-limit.max-tracked-clients", "100000"),
                Arguments.of("ceto.web.checkout-rate-limit.overflow-burst", "1"),
                Arguments.of("ceto.web.checkout-rate-limit.overflow-burst", "10000"),
                Arguments.of("ceto.web.checkout-rate-limit.overflow-refill-per-hour", "1"),
                Arguments.of("ceto.web.checkout-rate-limit.overflow-refill-per-hour", "3600"));
    }

    @Test
    void theOverflowRefillPerHourBecomesTheIntervalBetweenTokens() {
        assertThat(new WebProperties.CheckoutRateLimit(10, 360, 100, 100, 1).overflowRefillInterval())
                .isEqualTo(java.time.Duration.ofHours(1));
        assertThat(new WebProperties.CheckoutRateLimit(10, 360, 100, 100, 7).overflowRefillInterval())
                .as("rounded down to the millisecond").isEqualTo(java.time.Duration.ofMillis(514_285));
        assertThat(new WebProperties.CheckoutRateLimit(10, 360, 100, 100, 3_600).overflowRefillInterval())
                .isEqualTo(java.time.Duration.ofSeconds(1));
    }

    @ParameterizedTest(name = "{0}={1} is accepted")
    @MethodSource("boundaryValues")
    void startupAcceptsTheInclusiveBounds(String key, String value) {
        runner.withPropertyValues(key + "=" + value).run(context -> assertThat(context).hasNotFailed());
    }
}
