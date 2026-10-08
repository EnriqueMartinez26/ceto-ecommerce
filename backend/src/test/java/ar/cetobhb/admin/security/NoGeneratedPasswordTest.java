package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

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
        runner.withUserConfiguration(AdminAuthenticationConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InMemoryUserDetailsManager.class);
            assertThat(context).hasSingleBean(UserDetailsService.class);
        });

        assertThat(output.getAll()).doesNotContainIgnoringCase(BOOT_MESSAGE);
    }
}
