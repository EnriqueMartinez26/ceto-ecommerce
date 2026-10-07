package ar.cetobhb.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.annotation.Configuration;

class OrderPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(OrderProperties.class)
    static class Config {
    }

    /** Loads the real application.yml so the shipped defaults are what is under test. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void appliesTheDocumentedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OrderProperties p = context.getBean(OrderProperties.class);
            assertThat(p.reservationHours()).isEqualTo(48);
            assertThat(p.maxUnitsPerLine()).isEqualTo(10);
            assertThat(p.maxLinesPerOrder()).isEqualTo(20);
            assertThat(p.expiry().batchSize()).isEqualTo(100);
            assertThat(p.expiry().fixedDelayMs()).isEqualTo(60_000);
        });
    }

    @Test
    void honorsTheEnvironmentStyleOverrides() {
        runner.withSystemProperties("ORDER_RESERVATION_HOURS=72", "ORDER_MAX_UNITS_PER_LINE=25").run(context -> {
            assertThat(context).hasNotFailed();
            OrderProperties p = context.getBean(OrderProperties.class);
            assertThat(p.reservationHours()).isEqualTo(72);
            assertThat(p.maxUnitsPerLine()).isEqualTo(25);
        });
    }

    static Stream<Arguments> outOfRangeValues() {
        return Stream.of(
                Arguments.of("ceto.orders.reservation-hours", "0"),
                Arguments.of("ceto.orders.reservation-hours", "169"),
                Arguments.of("ceto.orders.max-units-per-line", "0"),
                Arguments.of("ceto.orders.max-units-per-line", "101"),
                Arguments.of("ceto.orders.max-lines-per-order", "0"),
                Arguments.of("ceto.orders.max-lines-per-order", "51"),
                Arguments.of("ceto.orders.expiry.batch-size", "0"),
                Arguments.of("ceto.orders.expiry.batch-size", "1001"),
                Arguments.of("ceto.orders.expiry.fixed-delay-ms", "999"),
                Arguments.of("ceto.orders.expiry.fixed-delay-ms", "3600001"));
    }

    @ParameterizedTest(name = "{0}={1} fails startup")
    @MethodSource("outOfRangeValues")
    void startupFailsForOutOfRangeValues(String key, String value) {
        runner.withPropertyValues(key + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            // Nested properties are reported in camelCase (expiry.batchSize); compare ignoring separators and case.
            java.io.StringWriter trace = new java.io.StringWriter();
            context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT))
                    .contains(key.replace("-", "").toLowerCase(java.util.Locale.ROOT));
        });
    }

    static Stream<Arguments> boundaryValues() {
        return Stream.of(
                Arguments.of("ceto.orders.reservation-hours", "1"),
                Arguments.of("ceto.orders.reservation-hours", "168"),
                Arguments.of("ceto.orders.max-units-per-line", "1"),
                Arguments.of("ceto.orders.max-units-per-line", "100"),
                Arguments.of("ceto.orders.max-lines-per-order", "1"),
                Arguments.of("ceto.orders.max-lines-per-order", "50"),
                Arguments.of("ceto.orders.expiry.batch-size", "1"),
                Arguments.of("ceto.orders.expiry.batch-size", "1000"),
                Arguments.of("ceto.orders.expiry.fixed-delay-ms", "1000"),
                Arguments.of("ceto.orders.expiry.fixed-delay-ms", "3600000"));
    }

    @ParameterizedTest(name = "{0}={1} is accepted")
    @MethodSource("boundaryValues")
    void startupAcceptsTheInclusiveBounds(String key, String value) {
        runner.withPropertyValues(key + "=" + value).run(context -> assertThat(context).hasNotFailed());
    }
}
