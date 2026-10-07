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
