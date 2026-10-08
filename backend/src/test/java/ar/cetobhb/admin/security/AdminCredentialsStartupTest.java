package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.support.AdminTestCredentials;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The credential is fail-closed at startup AND never leaks (REQ-AUTH-01, design AD-A4). This runs the real
 * {@code SpringApplication} with the real application.yml, so Boot's own failure analysis and logging are in play: they
 * are exactly what would print a rejected {@code {noop}secret} if the value were validated by the binder.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminCredentialsStartupTest {

    private static final String SENTINEL = "s3ntinel-Qw83";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminProperties.class)
    @Import({ValidationAutoConfiguration.class, AdminAuthenticationConfig.class})
    static class App {
    }

    /** Runs the application with the given properties; ADMIN_* are the environment variables the yml refers to. */
    private static ConfigurableApplicationContext run(Map<String, Object> properties) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties(properties)
                .run();
    }

    private static Map<String, Object> with(String username, String hash) {
        java.util.HashMap<String, Object> properties = new java.util.HashMap<>();
        if (username != null) {
            properties.put("ADMIN_USERNAME", username);
        }
        if (hash != null) {
            properties.put("ADMIN_PASSWORD_HASH", hash);
        }
        return properties;
    }

    private static Throwable failureOf(Supplier<ConfigurableApplicationContext> start) {
        Throwable failure = catchThrowable(() -> start.get().close());
        assertThat(failure).as("the application must refuse to start").isNotNull();
        return failure;
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void validCredentialsStartTheApplication() {
        try (ConfigurableApplicationContext context = run(
                with(AdminTestCredentials.username(), AdminTestCredentials.passwordHash()))) {
            assertThat(context.getBean(AdminCredentials.class).username()).isEqualTo(AdminTestCredentials.username());
        }
    }

    @Test
    void aWeakOrMalformedHashStopsTheStartupAndItsValueNeverReachesTheOutput(CapturedOutput output) {
        String[] bad = {"{noop}" + SENTINEL, SENTINEL, "{sha256}" + SENTINEL, "{bcrypt}" + SENTINEL,
            "{bcrypt" + "}$2a$09$" + SENTINEL, "{MD5}" + SENTINEL};

        for (String hash : bad) {
            Throwable failure = failureOf(() -> run(with(AdminTestCredentials.username(), hash)));

            assertThat(messagesOf(failure)).contains(AdminCredentials.INVALID_PASSWORD_HASH)
                    .doesNotContain(SENTINEL);
        }

        assertThat(output.getAll()).as("Boot's startup report and logs").contains("ceto.admin.password-hash")
                .doesNotContain(SENTINEL);
    }

    @Test
    void anUnsafeUsernameStopsTheStartupAndItsValueNeverReachesTheOutput(CapturedOutput output) {
        for (String username : new String[] {SENTINEL + " with space", SENTINEL + "\u0000", "", " "}) {
            Throwable failure = failureOf(() -> run(with(username, AdminTestCredentials.passwordHash())));

            assertThat(messagesOf(failure)).contains(AdminCredentials.INVALID_USERNAME).doesNotContain(SENTINEL);
        }

        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void anUnsetCredentialStopsTheStartup() {
        // The variables have no default; Boot binds the literal placeholder and the factory rejects it.
        assertThat(messagesOf(failureOf(() -> run(with(null, AdminTestCredentials.passwordHash())))))
                .contains(AdminCredentials.INVALID_USERNAME);
        assertThat(messagesOf(failureOf(() -> run(with(AdminTestCredentials.username(), null)))))
                .contains(AdminCredentials.INVALID_PASSWORD_HASH);
    }

    /**
     * Positive control: for a value that IS validated by the binder, Boot's report prints the value. If this stopped
     * being true the leak assertions above could pass because nothing is captured, so it is pinned.
     */
    @Test
    void controlBootsOwnReportEchoesAValueRejectedByBeanValidation(CapturedOutput output) {
        java.util.HashMap<String, Object> properties = new java.util.HashMap<>(
                with(AdminTestCredentials.username(), AdminTestCredentials.passwordHash()));
        properties.put("ADMIN_SESSION_IDLE_MINUTES", "99731");

        failureOf(() -> run(properties));

        assertThat(output.getAll()).contains("99731");
    }
}
