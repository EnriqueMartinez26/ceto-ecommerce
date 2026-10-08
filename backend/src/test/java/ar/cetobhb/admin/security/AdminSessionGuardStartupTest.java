package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The real {@code SpringApplication} with the real application.yml: the shipped settings start, and a weaker cookie
 * setting from a command-line style override stops the application, in the default profile and in any other
 * (REQ-AUTH-04 "Insecure cookie config"). The tests of the full context set nothing: the yml is the source.
 */
class AdminSessionGuardStartupTest {

    @Configuration(proxyBeanMethods = false)
    @Import(AdminSessionGuard.class)
    static class App {
    }

    /**
     * The overrides are command-line arguments, which beat application.yml (SpringApplicationBuilder.properties would
     * only set DEFAULT properties, below the yml, and the override would silently not apply).
     */
    private static ConfigurableApplicationContext run(Map<String, Object> properties, String... profiles) {
        String[] arguments = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF)
                .profiles(profiles).run(arguments);
    }

    @Test
    void theShippedApplicationYmlStartsTheGuard() {
        try (ConfigurableApplicationContext context = run(Map.of())) {
            assertThat(context.getBean(AdminSessionGuard.class)).isNotNull();
        }
    }

    @Test
    void theYmlSetsTheDocumentedCookieSettings() {
        try (ConfigurableApplicationContext context = run(Map.of())) {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
            assertThat(environment.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
            assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("strict");
            assertThat(environment.getProperty("server.servlet.session.tracking-modes")).isEqualTo("cookie");
        }
    }

    @Test
    void anInsecureCookieStopsTheStartupInTheDefaultProfileAndInProdAndTest() {
        for (String[] profiles : new String[][] {{}, {"prod"}, {"test"}, {"dev"}}) {
            Throwable failure = catchThrowable(
                    () -> run(Map.of("server.servlet.session.cookie.secure", "false"), profiles).close());

            assertThat(failure).as("profiles %s", String.join(",", profiles)).isNotNull();
            assertThat(messagesOf(failure)).contains("server.servlet.session.cookie.secure must be true");
        }
    }

    @Test
    void aMissingSecureFlagStopsTheStartup() {
        // An empty override is "not true": it must not fall back to Boot's own default of false silently.
        Throwable failure = catchThrowable(() -> run(Map.of("server.servlet.session.cookie.secure", "")).close());

        assertThat(failure).isNotNull();
        assertThat(messagesOf(failure)).contains("server.servlet.session.cookie.secure must be true");
    }

    @Test
    void aLaxSameSiteAndATrackingModeInTheUrlStopTheStartup() {
        assertThat(messagesOf(catchThrowable(
                () -> run(Map.of("server.servlet.session.cookie.same-site", "lax")).close())))
                .contains("same-site must be strict");
        assertThat(messagesOf(catchThrowable(
                () -> run(Map.of("server.servlet.session.tracking-modes", "cookie,url")).close())))
                .contains("tracking-modes must be cookie");
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
