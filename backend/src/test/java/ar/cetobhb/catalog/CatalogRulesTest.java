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

    @ParameterizedTest
    @ValueSource(strings = {"Ceto\0BHB", "Ceto\nBHB", "Ceto\tBHB", "Ceto\u0007BHB", "Ceto\u007fBHB",
            "Ceto​BHB", "Ceto‮BHB", "﻿Ceto", "Ceto­BHB"})
    void requireNameRejectsControlAndFormatCharacters(String name) {
        assertInvalid(ReasonCode.INVALID_CHARACTERS, () -> CatalogRules.requireName(name));
    }

    @Test
    void requireNameAcceptsAccentsAndNormalPunctuationWithoutRewritingTheText() {
        assertThatCode(() -> CatalogRules.requireName("Cápsulas Ceto BHB (60 u.) - 100% natural, ¿sí?"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireName("<b>Tom & Jerry</b> ñ")).doesNotThrowAnyException();
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

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01", "-1"})
    void zeroOrNegativePriceIsRejected(String price) {
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> CatalogRules.requireValidPrices(new BigDecimal(price), null));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> CatalogRules.requireValidPrices(new BigDecimal("5.00"), new BigDecimal(price)));
    }

    @Test
    void theSmallestPositivePriceIsAccepted() {
        assertThatCode(() -> CatalogRules.requireValidPrices(new BigDecimal("0.01"), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireValidPrices(new BigDecimal("0.01"), new BigDecimal("0.02")))
                .doesNotThrowAnyException();
    }

    @Test
    void priceWithMoreThanTwoDecimalsIsRejectedButTrailingZerosAreFine() {
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> CatalogRules.requireValidPrices(new BigDecimal("1.001"), null));
        assertThatCode(() -> CatalogRules.requireValidPrices(new BigDecimal("1.500"), null))
                .doesNotThrowAnyException();
    }

    @Test
    void compareAtPriceMustBeStrictlyGreaterThanThePrice() {
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> CatalogRules.requireValidPrices(new BigDecimal("10.00"), new BigDecimal("10.00")));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> CatalogRules.requireValidPrices(new BigDecimal("10.00"), new BigDecimal("9.99")));
        assertThatCode(() -> CatalogRules.requireValidPrices(new BigDecimal("10.00"), new BigDecimal("10.01")))
                .doesNotThrowAnyException();
    }

    @Test
    void comboUnitsMustBeAtLeastTwo() {
        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> CatalogRules.requireComboUnits(1));
        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> CatalogRules.requireComboUnits(0));
        assertThatCode(() -> CatalogRules.requireComboUnits(2)).doesNotThrowAnyException();
    }

    private static void assertInvalid(ReasonCode reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ValidationException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(reason));
    }
}
