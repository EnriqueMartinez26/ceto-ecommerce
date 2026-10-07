package ar.cetobhb.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberNormalizerTest {

    private final PhoneNumberNormalizer normalizer = new PhoneNumberNormalizer();

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "0351 15 555-1234;+5493515551234",
        "351 15 555 1234;+5493515551234",
        "+54 9 351 555 1234;+5493515551234",
        "+54 351 15 555 1234;+5493515551234",
        "+54 9 351 555-1234;+5493515551234",
        "(0351) 15-555-1234;+5493515551234",
        "011 15 5555-1234;+5491155551234",
        "11 15 5555 1234;+5491155551234",
        "+54 9 11 5555-1234;+5491155551234",
        "+5491155551234;+5491155551234",
        "351 555-1234;+5493515551234",
        "3515551234;+5493515551234",
        "0351 555-1234;+5493515551234",
        "011 5555-1234;+5491155551234",
        "11 5555 1234;+5491155551234",
        "+54 351 555 1234;+5493515551234"
    })
    void normalizesArgentineMobilesToE164(String input, String expected) {
        String result = normalizer.normalize(input);

        assertThat(result).isEqualTo(expected).matches("^\\+[1-9][0-9]{7,14}$");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\u202A+54 9 351 555-1234\u202C",
        "351\u00A0555\u20131234",
        "+54 9 351 555\u20111234",
        "\u200E+54\u202F9\u2009351\u3000555\u22121234\u200F",
        "\u2066+54 9 351 555 1234\u2069",
        "+54\t9 351 555 1234",
        "+54\u200B9\u200C 351\u200D 555\uFEFF1234\u00AD",
        "\uFF0B54 9 351 555\uFF0D1234",
        "+54 9 \uFF13\uFF15\uFF11 555 \uFF11\uFF12\uFF13\uFF14"
    })
    void acceptsUnicodeLookalikesFromCopyPaste(String input) {
        assertThat(normalizer.normalize(input)).isEqualTo("+5493515551234");
    }

    // Boundary tests for the Unicode ranges: first and last code point of each range are folded, the neighbours
    // just outside are left untouched and rejected by the charset check.

    @ParameterizedTest
    @ValueSource(ints = {0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069})
    void dropsEveryBidiMarkIncludingTheFirstAndLastOfEachRange(int codePoint) {
        String input = "+54 9 351 555" + Character.toString(codePoint) + "1234";

        assertThat(normalizer.normalize(input)).isEqualTo("+5493515551234");
    }

    @ParameterizedTest
    @ValueSource(ints = {0x2029, 0x2065, 0x206A})
    void doesNotDropCodePointsJustOutsideTheBidiRanges(int codePoint) {
        String input = "+54 9 351 555" + Character.toString(codePoint) + "1234";

        assertInvalidPhone(input);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212, 0xFF0D})
    void foldsEveryDashToAHyphenIncludingTheFirstAndLastOfTheRange(int codePoint) {
        String input = "+54 9 351 555" + Character.toString(codePoint) + "1234";

        assertThat(normalizer.normalize(input)).isEqualTo("+5493515551234");
    }

    @ParameterizedTest
    @ValueSource(ints = {0x2016, 0x2211, 0xFF0E})
    void doesNotFoldCodePointsJustOutsideTheDashSet(int codePoint) {
        String input = "+54 9 351 555" + Character.toString(codePoint) + "1234";

        assertInvalidPhone(input);
    }

    @Test
    void foldsTheFirstAndLastFullwidthDigit() {
        // U+FF10 (0) and U+FF19 (9) are the edges of the fullwidth digit range.
        assertThat(normalizer.normalize("+54 9 351 555 ０９０９")).isEqualTo("+5493515550909");
    }

    @ParameterizedTest
    @ValueSource(ints = {0xFF0F, 0xFF1A})
    void doesNotFoldCodePointsJustOutsideTheFullwidthDigits(int codePoint) {
        String input = "+54 9 351 555 123" + Character.toString(codePoint);

        assertInvalidPhone(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "+66 2 123 4567",   // Thai Bangkok landline: "+66 9 2123 4567" would be a valid mobile
        "+51 1 123 4567",   // Peruvian Lima landline: "+51 9 1123 4567" would be a valid mobile
        "+385 1 234 5678"   // Croatian Zagreb landline: "+385 9 1234 5678" would be a valid mobile
    })
    void doesNotTreatForeignFixedLinesAsAmbiguousArgentineNumbers(String landline) throws Exception {
        // D-20261007-11 applies to Argentine numbers only. Each of these becomes a valid MOBILE if a 9 is
        // inserted after the country code, so a normalizer that applied the rule to any region would accept them.
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        assertThat(util.getNumberType(util.parse(landline, "AR"))).isEqualTo(PhoneNumberType.FIXED_LINE);

        assertInvalidPhone(landline);
    }

    @Test
    void rejectsAnArgentineTollFreeNumberAndDoesNotReadItAsAMobile() throws Exception {
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        assertThat(util.getNumberType(util.parse("0800 555 1234", "AR"))).isEqualTo(PhoneNumberType.TOLL_FREE);

        assertInvalidPhone("0800 555 1234");
        assertInvalidPhone("+54 800 555 1234");
    }

    @Test
    void readsAnArgentineTenDigitFixedLineAsTheMobileWithTheNinePrefix() throws Exception {
        // Typed FIXED_LINE (no 15, no 9): ambiguous, so it is read as +54 9 plus the national number.
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        assertThat(util.getNumberType(util.parse("+54 351 555 1234", "AR"))).isEqualTo(PhoneNumberType.FIXED_LINE);

        assertThat(normalizer.normalize("+54 351 555 1234")).isEqualTo("+5493515551234");
    }

    private void assertInvalidPhone(String input) {
        assertThatThrownBy(() -> normalizer.normalize(input))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_PHONE));
    }

    @Test
    void stillRejectsLettersHiddenAmongLookalikes() {
        assertThatThrownBy(() -> normalizer.normalize("\u202A+54 9 351 555 CASA\u202C"))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_PHONE));
    }

    @Test
    void acceptsAnyValidForeignMobileInInternationalForm() {
        // Uruguayan mobile: the rule is "valid MOBILE number", not "Argentine only".
        assertThat(normalizer.normalize("+598 94 231 234")).isEqualTo("+59894231234");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "abc", "351 15 555", "   ", "+54 9 351 555 1234 ext 5", "+54 351 555 1234 x7",
        "+54 9 351 555 12345", "351 15 5 CASA", "+54 9 351 555 CASA","0800 555 1234", "555-1234"})
    @NullAndEmptySource
    void rejectsNonMobileOrMalformedInput(String input) {
        assertThatThrownBy(() -> normalizer.normalize(input))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_PHONE));
    }

    @Test
    void rejectsAForeignFixedLineEvenInInternationalForm() throws Exception {
        // Montevideo landline: the AR 10-digit rule (D-20261007-11) must not leak to other regions.
        String landline = "+598 2 901 2345";
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        assertThat(util.getNumberType(util.parse(landline, "AR"))).isEqualTo(PhoneNumberType.FIXED_LINE);

        assertThatThrownBy(() -> normalizer.normalize(landline))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_PHONE));
    }

    @Test
    void exceptionMessageNeverContainsThePhone() {
        assertThatThrownBy(() -> normalizer.normalize("+54 9 351 555 12345"))
                .hasMessage("VALIDATION_ERROR(INVALID_PHONE)")
                .hasMessageNotContaining("555")
                .hasMessageNotContaining("351");
    }

    @Test
    void fixedLineOrMobileIsTreatedAsInvalid() throws Exception {
        // D-20261007-06: only an unambiguous MOBILE is accepted. libphonenumber answers FIXED_LINE_OR_MOBILE for
        // some regions (US numbers, where the numbering plan does not split the two); that type is rejected.
        String ambiguous = "+1 650 253 0000";
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        assertThat(util.getNumberType(util.parse(ambiguous, "AR"))).isEqualTo(PhoneNumberType.FIXED_LINE_OR_MOBILE);

        assertThatThrownBy(() -> normalizer.normalize(ambiguous))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_PHONE));
    }
}
