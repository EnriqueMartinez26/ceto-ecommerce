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

    /** Names that look empty but are not: no code point a person can see. */
    @ParameterizedTest
    @ValueSource(ints = {0x00A0, 0x2007, 0x202F, 0x3000, 0x3164, 0xFFA0, 0x2800, 0x115F, 0x1160})
    void requireNameRejectsANameWithNoVisibleCodePoint(int codePoint) {
        String lone = new String(Character.toChars(codePoint));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> CatalogRules.requireName(lone));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> CatalogRules.requireName(lone + lone + lone));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> CatalogRules.requireName(" " + lone + " "));
    }

    @Test
    void requireNameRejectsAMixOfDifferentInvisibleCodePoints() {
        String mix = cp(0x00A0) + cp(0x3164) + cp(0x2800) + cp(0xFFA0) + cp(0x115F) + cp(0x1160);
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> CatalogRules.requireName(mix));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x2028, 0x2029, 0xE000, 0xF8FF, 0xF0000, 0x0378, 0x10FFFF})
    void requireNameRejectsLineSeparatorsPrivateUseAndUnassignedCodePoints(int codePoint) {
        String name = "Ceto" + cp(codePoint) + "BHB";
        assertInvalid(ReasonCode.INVALID_CHARACTERS, () -> CatalogRules.requireName(name));
    }

    @Test
    void requireNameStillAcceptsAnInvisibleLookingCodePointNextToVisibleText() {
        // A visible code point is enough; the filler is not rewritten or removed.
        assertThatCode(() -> CatalogRules.requireName("A" + cp(0x3164))).doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireName(cp(0x00A0) + "A" + cp(0x00A0))).doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireName(cp(0x2800) + "Pack")).doesNotThrowAnyException();
    }

    @Test
    void requireNameAcceptsSpanishNamesWithAccentsEnyeAndAnEmojiWithoutAJoiner() {
        assertThatCode(() -> CatalogRules.requireName("Cápsulas de café ñandú 🌿")).doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireName("Frasco 60 ☕ BHB")).doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireName("🌿")).doesNotThrowAnyException();
    }

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
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
    void comboUnitsMustBeBetweenTwoAndOneHundred() {
        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> CatalogRules.requireComboUnits(1));
        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> CatalogRules.requireComboUnits(0));
        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> CatalogRules.requireComboUnits(101));
        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> CatalogRules.requireComboUnits(Integer.MAX_VALUE));
        assertThatCode(() -> CatalogRules.requireComboUnits(2)).doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.requireComboUnits(100)).doesNotThrowAnyException();
    }

    private static void assertInvalid(ReasonCode reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ValidationException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(reason));
    }
}
