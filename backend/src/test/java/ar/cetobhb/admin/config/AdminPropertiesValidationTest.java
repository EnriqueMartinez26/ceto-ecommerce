package ar.cetobhb.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.security.AdminCredentials;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Fail-closed admin settings (HRD-12, AUTH-09, AUTH-10): session idle time, the login limiter bounds and the credential
 * keys. The runner loads the real application.yml, so the shipped defaults are what is under test.
 */
class AdminPropertiesValidationTest {

    private static final String PREFIX = "ceto.admin.";

    @Configuration
    @EnableConfigurationProperties(AdminProperties.class)
    static class Config {
    }

    /** Real application.yml plus a placeholder credential (the keys have no default and the tests need them to bind). */
    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues(PREFIX + "username=owner", PREFIX + "password-hash=placeholder");

    private static void assertFailureNames(AssertableApplicationContext context, String property) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).hasStackTraceContaining(property);
    }

    @Test
    void theShippedDefaultsAreTheDocumentedOnes() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            AdminProperties p = context.getBean(AdminProperties.class);
            assertThat(p.sessionIdleTimeoutMinutes()).isEqualTo(480);
            assertThat(p.loginRateLimit().burst()).isEqualTo(5);
            assertThat(p.loginRateLimit().refillIntervalSeconds()).isEqualTo(90);
            assertThat(p.loginRateLimit().maxTrackedClients()).isEqualTo(10_000);
            assertThat(p.loginRateLimit().overflowBurst()).isEqualTo(20);
            assertThat(p.loginRateLimit().overflowRefillPerHour()).isEqualTo(40);
            assertThat(p.maxListedProducts()).isEqualTo(200);
            assertThat(p.maxReconciliationRows()).isEqualTo(100);
        });
    }

    @Test
    void theSessionIdleTimeoutComesFromAdminSessionIdleMinutesWhenThatVariableIsSet() {
        shipped.withPropertyValues("ADMIN_SESSION_IDLE_MINUTES=30")
                .run(context -> assertThat(context.getBean(AdminProperties.class).sessionIdleTimeoutMinutes())
                        .isEqualTo(30));
    }

    private static final String GOOD_HASH = "{bcrypt}" + new BCryptPasswordEncoder(10).encode("a-long-test-password");

    /**
     * application.yml maps the keys to ${ADMIN_USERNAME} and ${ADMIN_PASSWORD_HASH}, which have no default. Boot's
     * binder does not fail on an unresolvable placeholder: it binds the literal text. So the binding itself succeeds
     * and the credential factory is what stops the startup (proved end to end in {@code AdminCredentialsStartupTest}).
     */
    @Test
    void anUnsetUsernameBindsAsTheLiteralPlaceholderAndTheFactoryRejectsIt() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .withPropertyValues(PREFIX + "password-hash=" + GOOD_HASH)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AdminProperties p = context.getBean(AdminProperties.class);
                    assertThat(p.username()).isEqualTo("${ADMIN_USERNAME}");
                    assertThatThrownBy(() -> AdminCredentials.parse(p.username(), p.passwordHash()))
                            .hasMessage(AdminCredentials.INVALID_USERNAME);
                });
    }

    @Test
    void anUnsetPasswordHashBindsAsTheLiteralPlaceholderAndTheFactoryRejectsIt() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .withPropertyValues(PREFIX + "username=owner")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AdminProperties p = context.getBean(AdminProperties.class);
                    assertThat(p.passwordHash()).isEqualTo("${ADMIN_PASSWORD_HASH}");
                    assertThatThrownBy(() -> AdminCredentials.parse(p.username(), p.passwordHash()))
                            .hasMessage(AdminCredentials.INVALID_PASSWORD_HASH);
                });
    }

    static Stream<Arguments> outOfRangeValues() {
        return Stream.of(
                Arguments.of("session-idle-timeout-minutes", "0"),
                Arguments.of("session-idle-timeout-minutes", "4"),
                Arguments.of("session-idle-timeout-minutes", "1441"),
                Arguments.of("session-idle-timeout-minutes", "10080"),
                Arguments.of("login-rate-limit.burst", "0"),
                Arguments.of("login-rate-limit.burst", "21"),
                Arguments.of("login-rate-limit.burst", "1000"),
                Arguments.of("login-rate-limit.refill-interval-seconds", "29"),
                Arguments.of("login-rate-limit.refill-interval-seconds", "3601"),
                Arguments.of("login-rate-limit.max-tracked-clients", "99"),
                Arguments.of("login-rate-limit.max-tracked-clients", "100001"),
                Arguments.of("login-rate-limit.overflow-burst", "0"),
                Arguments.of("login-rate-limit.overflow-burst", "1001"),
                Arguments.of("login-rate-limit.overflow-refill-per-hour", "0"),
                Arguments.of("login-rate-limit.overflow-refill-per-hour", "3601"));
    }

    @ParameterizedTest(name = "{0}={1} aborts startup")
    @MethodSource("outOfRangeValues")
    void anOutOfRangeValueAbortsStartupAndNamesTheProperty(String key, String value) {
        shipped.withPropertyValues(PREFIX + key + "=" + value).run(context -> {
            String camel = key.contains("login-rate-limit.")
                    ? "loginRateLimit." + toCamel(key.substring("login-rate-limit.".length()))
                    : toCamel(key);
            assertFailureNames(context, PREFIX.substring(0, PREFIX.length() - 1) + "." + camel);
        });
    }

    private static String toCamel(String kebab) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    static Stream<Arguments> boundaryValues() {
        return Stream.of(
                Arguments.of("session-idle-timeout-minutes", "5"),
                Arguments.of("session-idle-timeout-minutes", "1440"),
                Arguments.of("login-rate-limit.burst", "1"),
                Arguments.of("login-rate-limit.burst", "20"),
                Arguments.of("login-rate-limit.refill-interval-seconds", "30"),
                Arguments.of("login-rate-limit.refill-interval-seconds", "3600"),
                Arguments.of("login-rate-limit.max-tracked-clients", "100"),
                Arguments.of("login-rate-limit.max-tracked-clients", "100000"),
                Arguments.of("login-rate-limit.overflow-burst", "1"),
                Arguments.of("login-rate-limit.overflow-burst", "1000"),
                Arguments.of("login-rate-limit.overflow-refill-per-hour", "1"),
                Arguments.of("login-rate-limit.overflow-refill-per-hour", "3600"));
    }

    @ParameterizedTest(name = "{0}={1} is accepted")
    @MethodSource("boundaryValues")
    void theInclusiveBoundsAreAccepted(String key, String value) {
        shipped.withPropertyValues(PREFIX + key + "=" + value).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theOverflowRefillPerHourBecomesTheIntervalBetweenTokens() {
        assertThat(new AdminProperties.LoginRateLimit(5, 90, 100, 20, 1).overflowRefillInterval())
                .isEqualTo(Duration.ofHours(1));
        assertThat(new AdminProperties.LoginRateLimit(5, 90, 100, 20, 40).overflowRefillInterval())
                .isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void toStringNeverPrintsTheUsernameOrTheHash() {
        AdminProperties properties = new AdminProperties("owner-name-sentinel", "hash-sentinel-value", 480,
                new AdminProperties.LoginRateLimit(5, 90, 10_000, 20, 40), 200, 100);

        assertThat(properties.toString()).doesNotContain("owner-name-sentinel").doesNotContain("hash-sentinel-value")
                .contains("480");
    }
}
