package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Each strict setting is pinned by the request it must reject (verification U-1, U-5). */
class JacksonConfigTest {

    record Line(Long productId, Integer quantity) {
    }

    record Order(List<Line> lines, String note) {
    }

    enum Zone {
        CORDOBA_CAPITAL
    }

    record Scalars(String text, Zone zone, UUID id, LocalDate day, Instant at, BigDecimal amount) {
    }

    record Flags(boolean active, long count) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    private void withMapper(java.util.function.Consumer<JsonMapper> test) {
        runner.run(context -> test.accept(context.getBean(JsonMapper.class)));
    }

    private static void assertRejected(JsonMapper mapper, String json) {
        assertThatThrownBy(() -> mapper.readValue(json, Order.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void acceptsAWellFormedBody() {
        withMapper(mapper -> {
            Order order = mapper.readValue("{\"lines\":[{\"productId\":1,\"quantity\":2}],\"note\":\"hi\"}", Order.class);
            assertThat(order.lines()).containsExactly(new Line(1L, 2));
        });
    }

    @Test
    void rejectsAStrayProperty() {
        withMapper(mapper -> assertRejected(mapper, "{\"lines\":[{\"productId\":1,\"quantity\":2,\"price\":1}]}"));
    }

    @Test
    void rejectsAStringWhereANumberIsExpected() {
        withMapper(mapper -> assertRejected(mapper, "{\"lines\":[{\"productId\":1,\"quantity\":\"2\"}]}"));
    }

    @Test
    void keepsAcceptingJsonStringsForStringBackedScalars() {
        String json = "{\"text\":\"hi\",\"zone\":\"CORDOBA_CAPITAL\",\"id\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"day\":\"2026-10-07\",\"at\":\"2026-10-07T12:00:00Z\",\"amount\":\"1234.50\"}";
        withMapper(mapper -> {
            Scalars scalars = mapper.readValue(json, Scalars.class);
            assertThat(scalars.text()).isEqualTo("hi");
            assertThat(scalars.zone()).isEqualTo(Zone.CORDOBA_CAPITAL);
            assertThat(scalars.id()).isEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
            assertThat(scalars.day()).isEqualTo(LocalDate.of(2026, 10, 7));
            assertThat(scalars.at()).isEqualTo(Instant.parse("2026-10-07T12:00:00Z"));
            // Decision: money travels as a JSON string in responses, so BigDecimal-from-string stays allowed.
            assertThat(scalars.amount()).isEqualByComparingTo("1234.50");
        });
    }

    @Test
    void rejectsAStringWhereALongOrABooleanIsExpected() {
        withMapper(mapper -> {
            assertThatThrownBy(() -> mapper.readValue("{\"active\":true,\"count\":\"123\"}", Flags.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> mapper.readValue("{\"active\":\"true\",\"count\":1}", Flags.class))
                    .isInstanceOf(JacksonException.class);
            assertThat(mapper.readValue("{\"active\":true,\"count\":1}", Flags.class)).isEqualTo(new Flags(true, 1L));
        });
    }

    @Test
    void rejectsAFloatWhereAnIntegerIsExpected() {
        withMapper(mapper -> assertRejected(mapper, "{\"lines\":[{\"productId\":1,\"quantity\":2.0}]}"));
    }

    @Test
    void rejectsADuplicateKey() {
        withMapper(mapper -> assertRejected(mapper, "{\"note\":\"a\",\"note\":\"b\"}"));
    }

    @Test
    void rejectsTrailingTokens() {
        withMapper(mapper -> assertRejected(mapper, "{\"note\":\"a\"} {\"note\":\"b\"}"));
    }

    @Test
    void rejectsNestingDeeperThanEight() {
        String deep = "[".repeat(9) + "]".repeat(9);
        String ok = "[".repeat(8) + "]".repeat(8);
        withMapper(mapper -> {
            assertThatThrownBy(() -> mapper.readTree(deep)).isInstanceOf(JacksonException.class);
            assertThat(mapper.readTree(ok)).isNotNull();
        });
    }

    @Test
    void rejectsAnOverlongString() {
        String json = "{\"note\":\"" + "x".repeat(4097) + "\"}";
        withMapper(mapper -> assertRejected(mapper, json));
    }

    @Test
    void rejectsAnOverlongName() {
        String json = "{\"" + "n".repeat(65) + "\":1}";
        withMapper(mapper -> assertRejected(mapper, json));
    }
}
