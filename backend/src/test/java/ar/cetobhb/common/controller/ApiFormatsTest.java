package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApiFormatsTest {

    @ParameterizedTest
    @CsvSource({"45000,45000.00", "120000.5,120000.50", "1E+3,1000.00", "0,0.00", "99.99,99.99", "100.00,100.00"})
    void moneyIsAPlainStringWithTwoDecimals(String input, String expected) {
        assertThat(ApiFormats.money(new BigDecimal(input))).isEqualTo(expected);
    }

    @Test
    void moneyOfNullIsNull() {
        assertThat(ApiFormats.money(null)).isNull();
    }

    @Test
    void moneyNeverRoundsSilently() {
        assertThatThrownBy(() -> ApiFormats.money(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void instantIsIso8601UtcTruncatedToSeconds() {
        assertThat(ApiFormats.instant(Instant.parse("2026-10-07T12:34:56.789123Z"))).isEqualTo("2026-10-07T12:34:56Z");
        assertThat(ApiFormats.instant(Instant.parse("2026-10-07T12:34:56Z"))).isEqualTo("2026-10-07T12:34:56Z");
    }

    @Test
    void instantOfNullIsNull() {
        assertThat(ApiFormats.instant(null)).isNull();
    }
}
