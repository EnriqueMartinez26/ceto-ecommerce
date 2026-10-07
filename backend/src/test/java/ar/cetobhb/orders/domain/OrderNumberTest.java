package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderNumberTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "1, CETO-000001",
        "123, CETO-000123",
        "999999, CETO-999999",
        "1000000, CETO-1000000",
        "9223372036854775807, CETO-9223372036854775807"
    })
    void formatsWithSixDigitPaddingAndNeverTruncates(long sequenceValue, String expected) {
        assertThat(OrderNumber.format(sequenceValue)).isEqualTo(expected);
    }

    @Test
    void formattingIgnoresTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertThat(OrderNumber.format(42)).isEqualTo("CETO-000042");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "-1000000"})
    void nonPositiveSequenceValuesAreRejected(long value) {
        assertThatThrownBy(() -> OrderNumber.format(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
