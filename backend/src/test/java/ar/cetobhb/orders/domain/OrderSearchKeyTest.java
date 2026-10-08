package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderSearchKeyTest {

    private static final PhoneNumberNormalizer PHONE = new PhoneNumberNormalizer();

    private static Optional<OrderSearchKey> parse(String q) {
        return OrderSearchKey.parse(q, PHONE);
    }

    @Test
    void anOrderNumberIsAnExactNumberLookup() {
        OrderSearchKey key = parse("CETO-000123").orElseThrow();

        assertThat(key.number()).isEqualTo("CETO-000123");
        assertThat(key.phone()).isNull();
    }

    @Test
    void wideOrderNumbersKeepAllTheirDigits() {
        assertThat(parse("CETO-1234567").orElseThrow().number()).isEqualTo("CETO-1234567");
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertThat(parse("  CETO-000123 ").orElseThrow().number()).isEqualTo("CETO-000123");
    }

    @Test
    void shortDigitsAreAnOrderNumberAndAlsoAPhoneAttempt() {
        OrderSearchKey key = parse("123").orElseThrow();

        assertThat(key.number()).isEqualTo("CETO-000123");
        // 123 is not a valid phone, so only the number candidate is left.
        assertThat(key.phone()).isNull();
    }

    @Test
    void tenDigitsWithoutSeparatorsAreAPhoneNotAnOrderNumber() {
        OrderSearchKey key = parse("3515550199").orElseThrow();

        assertThat(key.phone()).isEqualTo("+5493515550199");
        assertThat(key.number()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"351 555-0199", "+54 9 351 555 0199", "(351) 555.0199", "+5493515550199"})
    void anyWrittenFormOfAPhoneGivesTheE164Phone(String q) {
        OrderSearchKey key = parse(q).orElseThrow();

        assertThat(key.phone()).isEqualTo("+5493515550199");
        assertThat(key.number()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CETO-0001", "CETO-00012", "ceto-000123", "CETO-0000123", "CETO-", "CETO-000000",
            "CETO-000123x", "hello", "%", "_", "1 OR 1=1", "a%b", "0", "00", "+", "12345678901234567890"})
    void whateverIsNeitherANumberNorAPhoneGivesNoKey(String q) {
        assertThat(parse(q)).isEmpty();
    }

    @Test
    void blankAndNullGiveNoKey() {
        assertThat(parse(null)).isEmpty();
        assertThat(parse("")).isEmpty();
        assertThat(parse("   ")).isEmpty();
    }

    @Test
    void inputOverTheLimitIsRejectedBeforeParsing() {
        String longPhone = "3".repeat(OrderSearchKey.MAX_QUERY_LENGTH + 1);
        String longNumber = "CETO-" + "0".repeat(OrderSearchKey.MAX_QUERY_LENGTH);

        assertThat(parse(longPhone)).isEmpty();
        assertThat(parse(longNumber)).isEmpty();
        assertThat(parse("CETO-" + "1".repeat(OrderSearchKey.MAX_QUERY_LENGTH - 5))).isEmpty();
    }

    @Test
    void aNumberTooLargeForALongGivesNoKey() {
        assertThat(parse("CETO-99999999999999999999")).isEmpty();
    }

    @Test
    void neverProducesAWildcardOrAPattern() {
        for (String q : new String[] {"CETO-000123", "123", "351 555-0199", "9999999"}) {
            parse(q).ifPresent(key -> {
                if (key.number() != null) {
                    assertThat(key.number()).matches("CETO-[0-9]{6,}");
                }
                if (key.phone() != null) {
                    assertThat(key.phone()).matches("\\+[1-9][0-9]{7,14}");
                }
            });
        }
    }

    @Test
    void theNormalizerIsTheOnlyPhoneRoute() {
        // An unrelated plain phone with letters must not turn into digits by any other route.
        assertThat(parse("CASA 351 555")).isEmpty();
    }
}
