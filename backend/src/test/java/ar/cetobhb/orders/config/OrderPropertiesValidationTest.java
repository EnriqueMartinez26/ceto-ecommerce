package ar.cetobhb.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    /** Without the real application.yml: only the properties given here exist, so absence is controllable. */
    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    private static java.util.Map<String, String> allKeys() {
        java.util.Map<String, String> keys = new java.util.LinkedHashMap<>();
        keys.put("ceto.orders.reservation-hours", "48");
        keys.put("ceto.orders.max-units-per-line", "10");
        keys.put("ceto.orders.max-lines-per-order", "20");
        keys.put("ceto.orders.expiry.batch-size", "100");
        keys.put("ceto.orders.expiry.fixed-delay-ms", "60000");
        keys.put("ceto.orders.expiry.max-batches-per-tick", "10");
        return keys;
    }

    private static String[] asPairs(java.util.Map<String, String> keys) {
        return keys.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    private static String trace(Throwable failure) {
        java.io.StringWriter out = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(out));
        return out.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void theBareRunnerWithAllKeysStarts() {
        bare.withPropertyValues(asPairs(allKeys())).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startupFailsWhenTheWholeExpiryBlockIsAbsent() {
        java.util.Map<String, String> keys = allKeys();
        keys.keySet().removeIf(k -> k.startsWith("ceto.orders.expiry."));
        bare.withPropertyValues(asPairs(keys)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(trace(context.getStartupFailure())).contains("expiry");
        });
    }

    @ParameterizedTest(name = "{0} absent fails startup")
    @ValueSource(strings = {"ceto.orders.reservation-hours", "ceto.orders.max-units-per-line",
        "ceto.orders.max-lines-per-order"})
    void startupFailsWhenATopLevelKeyIsAbsent(String key) {
        java.util.Map<String, String> keys = allKeys();
        keys.remove(key);
        bare.withPropertyValues(asPairs(keys)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(trace(context.getStartupFailure()))
                    .contains(key.substring("ceto.orders.".length()).replace("-", ""));
        });
    }

    @ParameterizedTest(name = "{0} blank fails startup")
    @ValueSource(strings = {"ceto.orders.reservation-hours", "ceto.orders.max-units-per-line",
        "ceto.orders.max-lines-per-order"})
    void startupFailsWhenATopLevelKeyIsBlank(String key) {
        java.util.Map<String, String> keys = allKeys();
        keys.put(key, "");
        bare.withPropertyValues(asPairs(keys)).run(context -> assertThat(context).hasFailed());
    }

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
            assertThat(p.expiry().maxBatchesPerTick()).isEqualTo(10);
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
                Arguments.of("ceto.orders.expiry.fixed-delay-ms", "3600001"),
                Arguments.of("ceto.orders.expiry.max-batches-per-tick", "0"),
                Arguments.of("ceto.orders.expiry.max-batches-per-tick", "101"));
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
                Arguments.of("ceto.orders.expiry.fixed-delay-ms", "3600000"),
                Arguments.of("ceto.orders.expiry.max-batches-per-tick", "1"),
                Arguments.of("ceto.orders.expiry.max-batches-per-tick", "100"));
    }

    @ParameterizedTest(name = "{0}={1} is accepted")
    @MethodSource("boundaryValues")
    void startupAcceptsTheInclusiveBounds(String key, String value) {
        runner.withPropertyValues(key + "=" + value).run(context -> assertThat(context).hasNotFailed());
    }
}
