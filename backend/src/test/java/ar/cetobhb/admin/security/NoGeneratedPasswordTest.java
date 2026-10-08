package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.support.AdminTestCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.config.annotation.authentication.configuration.EnableGlobalAuthentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Boot's default user must not appear (review fix batch 5, item 5). The first test is the positive control: without
 * our account store Boot's auto-configuration does create the user and prints the generated password, so the second
 * test cannot pass vacuously.
 */
@ExtendWith(OutputCaptureExtension.class)
class NoGeneratedPasswordTest {

    private static final String BOOT_MESSAGE = "generated security password";

    @Configuration(proxyBeanMethods = false)
    @EnableGlobalAuthentication
    static class SecurityInfrastructure {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminProperties.class)
    static class AdminPropertiesConfig {
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class,
                    UserDetailsServiceAutoConfiguration.class))
            .withUserConfiguration(SecurityInfrastructure.class);

    @Test
    void controlBootAloneCreatesTheUserAndLogsItsPassword(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InMemoryUserDetailsManager.class);
        });

        assertThat(output.getAll()).contains(BOOT_MESSAGE);
    }

    @Test
    void ourAccountStoreMakesBootCreateNoUserAndLogNoPassword(CapturedOutput output) {
        runner.withUserConfiguration(AdminAuthenticationConfig.class, AdminPropertiesConfig.class)
                .withPropertyValues("ceto.admin.username=" + AdminTestCredentials.username(),
                        "ceto.admin.password-hash=" + AdminTestCredentials.passwordHash(),
                        "ceto.admin.session-idle-timeout-minutes=480",
                        "ceto.admin.session-absolute-timeout-minutes=720",
                        "ceto.admin.max-listed-products=200", "ceto.admin.max-reconciliation-rows=100",
                        "ceto.admin.login-rate-limit.burst=5",
                        "ceto.admin.login-rate-limit.refill-interval-seconds=90",
                        "ceto.admin.login-rate-limit.max-tracked-clients=10000",
                        "ceto.admin.login-rate-limit.overflow-burst=20",
                        "ceto.admin.login-rate-limit.overflow-refill-per-hour=40")
                .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InMemoryUserDetailsManager.class);
            assertThat(context).hasSingleBean(UserDetailsService.class);
            assertThat(context.getBean(UserDetailsService.class)).isInstanceOf(AdminUserDetailsService.class);
        });

        assertThat(output.getAll()).doesNotContainIgnoringCase(BOOT_MESSAGE);
    }
}
