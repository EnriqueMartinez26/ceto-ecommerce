package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The real {@code SpringApplication} with the real application.yml (D-20261008-46): Spring Security at DEBUG prints
 * the authenticated account, the client address and the session id, so the application does not start when the
 * effective configuration puts {@code org.springframework.security} (or any sub-category set in the environment)
 * below WARN, or lets {@code ...authentication.dao} (which prints the typed username) out of OFF. Overrides come from
 * command-line arguments, from environment variables and from logging groups, in any profile. The guard checks the
 * configuration at startup; a runtime change through the logging system (as the PII tests do to prove the control) is
 * outside its reach, and no actuator exists to make one.
 */
class SecurityLogLevelGuardStartupTest {

    private static final String ROOT = "logging.level.org.springframework.security";
    private static final String DAO = "logging.level.org.springframework.security.authentication.dao";

    @Configuration(proxyBeanMethods = false)
    @Import(SecurityLogLevelGuard.class)
    static class App {
    }

    /** Command-line arguments beat application.yml, like a real override at deployment. */
    private static ConfigurableApplicationContext run(String... arguments) {
        return start(false, Map.of(), new String[0], arguments);
    }

    private static ConfigurableApplicationContext start(boolean lazy, Map<String, Object> variables, String[] profiles,
            String... arguments) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).lazyInitialization(lazy).profiles(profiles);
        if (!variables.isEmpty()) {
            builder.initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("test-systemEnvironment", variables)));
        }
        return builder.run(arguments);
    }

    private static void assertRefused(Throwable failure, String message) {
        assertThat(failure).isNotNull();
        assertThat(messagesOf(failure)).contains(message);
    }

    @Test
    void theShippedApplicationYmlStartsTheGuard() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getBean(SecurityLogLevelGuard.class)).isNotNull();
            assertThat(context.getEnvironment().getProperty("logging.level.org.springframework.security"))
                    .isEqualTo("WARN");
            assertThat(context.getEnvironment()
                    .getProperty("logging.level.org.springframework.security.authentication.dao")).isEqualTo("OFF");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "debug", "TRACE", "INFO", ""})
    void aCommandLineLevelBelowWarnOnTheSecurityCategoryStopsTheStartup(String level) {
        Throwable failure = catchThrowable(() -> run("--" + ROOT + "=" + level).close());

        assertRefused(failure, "logging.level.org.springframework.security must be WARN or higher");
    }

    @ParameterizedTest
    @ValueSource(strings = {"WARN", "ERROR", "OFF"})
    void warnErrorAndOffOnTheSecurityCategoryStart(String level) {
        try (ConfigurableApplicationContext context = run("--" + ROOT + "=" + level)) {
            assertThat(context.getBean(SecurityLogLevelGuard.class)).isNotNull();
        }
    }

    @Test
    void anEnvironmentVariableSpellingOfDebugStopsTheStartup() {
        Throwable failure = catchThrowable(() -> start(false, Map.of("LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY",
                "DEBUG"), new String[0]).close());

        assertRefused(failure, "logging.level.org.springframework.security must be WARN or higher");
    }

    @Test
    void aSubCategoryAtDebugStopsTheStartupFromTheCommandLineAndFromTheEnvironment() {
        Throwable fromArgument = catchThrowable(
                () -> run("--logging.level.org.springframework.security.web=DEBUG").close());
        Throwable fromEnvironment = catchThrowable(() -> start(false,
                Map.of("LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY_WEB", "TRACE"), new String[0]).close());

        assertRefused(fromArgument, "A logging.level entry under org.springframework.security is below WARN");
        assertRefused(fromEnvironment, "A logging.level entry under org.springframework.security is below WARN");
    }

    @Test
    void aSubCategoryAtErrorStartsBecauseItIsNotBelowWarn() {
        try (ConfigurableApplicationContext context = run("--logging.level.org.springframework.security.web=ERROR")) {
            assertThat(context.getBean(SecurityLogLevelGuard.class)).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "WARN", "ERROR", ""})
    void theDaoCategoryHasToStayOff(String level) {
        Throwable failure = catchThrowable(() -> run("--" + DAO + "=" + level).close());

        assertRefused(failure,
                "logging.level.org.springframework.security.authentication.dao must be OFF");
    }

    @Test
    void aLoggingGroupThatContainsASecurityCategoryAtDebugStopsTheStartup() {
        Throwable failure = catchThrowable(() -> run(
                "--logging.group.authsec=org.springframework.security.web,org.springframework.security.config",
                "--logging.level.authsec=DEBUG").close());

        assertRefused(failure, "A logging group that contains org.springframework.security is below WARN");
    }

    @Test
    void aLoggingGroupWithoutSecurityCategoriesIsNotTouched() {
        try (ConfigurableApplicationContext context = run("--logging.group.mine=ar.cetobhb.orders",
                "--logging.level.mine=DEBUG")) {
            assertThat(context.getBean(SecurityLogLevelGuard.class)).isNotNull();
        }
    }

    @Test
    void theGuardRunsInEveryProfile() {
        for (String profile : new String[] {"prod", "test", "dev", "default"}) {
            Throwable failure = catchThrowable(() -> start(false, Map.of(), new String[] {profile},
                    "--" + ROOT + "=DEBUG").close());

            assertRefused(failure, "logging.level.org.springframework.security must be WARN or higher");
        }
    }

    @Test
    void withLazyInitializationOnTheGuardStillRunsAtStartup() {
        Throwable failure = catchThrowable(() -> start(true, Map.of(), new String[0], "--" + ROOT + "=DEBUG").close());

        assertRefused(failure, "logging.level.org.springframework.security must be WARN or higher");
    }

    @Test
    void theFailureTextIsFixedAndNeverRepeatsTheValue() {
        Throwable failure = catchThrowable(() -> run("--" + ROOT + "=dEbUg").close());

        assertThat(failure).isNotNull();
        assertThat(messagesOf(failure)).contains("must be WARN or higher").doesNotContain("dEbUg");
    }

    @Test
    void aLevelNameBootDoesNotKnowIsRefusedByBootItselfBeforeTheGuardEvenRuns() {
        // Boot binds logging.level to its LogLevel enum while it initialises logging; the failure is Boot's own.
        Throwable failure = catchThrowable(() -> run("--" + ROOT + "=nonsense").close());

        assertThat(failure).isNotNull();
    }

    @Test
    void theBootDebugFlagDoesNotRaiseSecurityBecauseTheExplicitWarnWinsOverIt() {
        try (ConfigurableApplicationContext context = run("--debug=true")) {
            assertThat(context.getBean(SecurityLogLevelGuard.class)).isNotNull();
            assertThat(LoggerFactory.getLogger("org.springframework.security").isDebugEnabled()).isFalse();
        }
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
