package ar.cetobhb.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.CatalogRules;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogRulesTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    void requireNameRejectsNullAndBlank(String name) {
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> CatalogRules.requireName(name));
    }

    @Test
    void requireNameRejectsTrimmedLengthOverTwoHundred() {
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> CatalogRules.requireName("a".repeat(201)));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> CatalogRules.requireName(" " + "a".repeat(201) + " "));
    }

    @Test
    void requireNameAcceptsTwoHundredCharsEvenWithSurroundingWhitespace() {
        assertThatCode(() -> CatalogRules.requireName("a".repeat(200))).doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireName("  " + "a".repeat(200) + "  ")).doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireName("x")).doesNotThrowAnyException();
    }

    @Test
    void nullPriceIsAnInvalidPricePair() {
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR, () -> CatalogRules.requireValidPrices(null, null));
    }

    @Test
    void priceAboveNumeric12Dot2MaximumIsAnInvalidPricePair() {
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> CatalogRules.requireValidPrices(new BigDecimal("10000000000.00"), null));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> CatalogRules.requireValidPrices(new BigDecimal("1.00"), new BigDecimal("10000000000.00")));
        assertThatCode(() -> CatalogRules.requireValidPrices(new BigDecimal("9999999999.99"), null))
                .doesNotThrowAnyException();
    }

    private static void assertInvalid(ReasonCode reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ValidationException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(reason));
    }
}
