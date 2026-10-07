package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderLimitsTest {

    @Test
    void theSmallestValidLimitsAreOneAndOne() {
        OrderLimits limits = new OrderLimits(1, 1);

        assertThat(limits.maxUnitsPerLine()).isEqualTo(1);
        assertThat(limits.maxLinesPerOrder()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "1,0", "0,0", "-1,5", "5,-1"})
    void rejectsAnyLimitBelowOne(int maxUnitsPerLine, int maxLinesPerOrder) {
        assertThatThrownBy(() -> new OrderLimits(maxUnitsPerLine, maxLinesPerOrder))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
