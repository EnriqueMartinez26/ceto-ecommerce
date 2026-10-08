package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.support.AbstractHttpIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/**
 * Spring Security's {@code DaoAuthenticationProvider} logs "Failed to find user '&lt;username&gt;'" at DEBUG, and the
 * username a client types is attacker controlled. The category is silenced in {@code application.yml} on its own, so a
 * broader {@code org.springframework.security} DEBUG (someone debugging an incident) cannot print it.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminAuthenticationLoggingIT extends AbstractHttpIT {

    private static final String DAO_LOGGER = "org.springframework.security.authentication.dao";

    private static final String SENTINEL = "SentinelLoginName-91723";

    @Autowired
    @Qualifier("adminAuthenticationManager")
    private AuthenticationManager manager;

    @Autowired
    private LoggingSystem loggingSystem;

    private LogLevel configuredDaoLevel;

    @BeforeEach
    void rememberLevels() {
        configuredDaoLevel = loggingSystem.getLoggerConfiguration(DAO_LOGGER).getConfiguredLevel();
    }

    @AfterEach
    void restoreLevels() {
        loggingSystem.setLogLevel("org.springframework.security", null);
        loggingSystem.setLogLevel(DAO_LOGGER, configuredDaoLevel);
    }

    private void failToLogIn() {
        assertThatThrownBy(() -> manager
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(SENTINEL, "not-the-password")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void theDaoProviderCategoryIsOffInTheEffectiveConfig() {
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(DAO_LOGGER);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getEffectiveLevel()).isEqualTo(LogLevel.OFF);
    }

    @Test
    void positiveControlTheProviderPrintsTheTypedUsernameWhenItsOwnLoggerIsOnDebug(CapturedOutput output) {
        loggingSystem.setLogLevel(DAO_LOGGER, LogLevel.DEBUG);

        failToLogIn();

        assertThat(output.getAll()).as("proof that the DEBUG line exists and echoes the username").contains(SENTINEL);
    }

    @Test
    void aFailedLoginLeavesNoUsernameInTheOutputEvenWithSecurityOnDebug(CapturedOutput output) {
        loggingSystem.setLogLevel("org.springframework.security", LogLevel.DEBUG);

        failToLogIn();

        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }
}
