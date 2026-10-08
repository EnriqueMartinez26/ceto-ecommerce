package ar.cetobhb.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** The admin list and reconciliation caps (HRD-12): fail-closed, a minimum AND a maximum, shipped defaults 200 and 100. */
class AdminPropertiesCapsValidationTest {

    @Configuration
    @EnableConfigurationProperties(AdminProperties.class)
    static class Config {
    }

    /** Loads the real application.yml so the shipped defaults are what is under test. */
    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    /** Without application.yml: only the properties given here exist, so absence is controllable. */
    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void theShippedDefaultsAre200ProductsAnd100ReconciliationRows() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            AdminProperties properties = context.getBean(AdminProperties.class);
            assertThat(properties.maxListedProducts()).isEqualTo(200);
            assertThat(properties.maxReconciliationRows()).isEqualTo(100);
        });
    }

    @ParameterizedTest(name = "max-listed-products {0} aborts startup")
    @ValueSource(strings = {"0", "-1", "501", "2147483647"})
    void aProductCapOutsideOneTo500AbortsStartup(String value) {
        bare.withPropertyValues("ceto.admin.max-listed-products=" + value, "ceto.admin.max-reconciliation-rows=100")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest(name = "max-reconciliation-rows {0} aborts startup")
    @ValueSource(strings = {"0", "-1", "1001", "2147483647"})
    void aReconciliationCapOutsideOneTo1000AbortsStartup(String value) {
        bare.withPropertyValues("ceto.admin.max-listed-products=200", "ceto.admin.max-reconciliation-rows=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theBoundariesAreAccepted() {
        bare.withPropertyValues("ceto.admin.max-listed-products=1", "ceto.admin.max-reconciliation-rows=1")
                .run(context -> assertThat(context).hasNotFailed());
        bare.withPropertyValues("ceto.admin.max-listed-products=500", "ceto.admin.max-reconciliation-rows=1000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void anAbsentCapAbortsStartupBecauseAMissingNumberBindsToZero() {
        bare.withPropertyValues("ceto.admin.max-reconciliation-rows=100")
                .run(context -> assertThat(context).hasFailed());
        bare.withPropertyValues("ceto.admin.max-listed-products=200")
                .run(context -> assertThat(context).hasFailed());
    }
}
