package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderTotalsTest {

    private static PricedLine line(String price, int qty) {
        return new PricedLine(new BigDecimal(price), qty);
    }

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource({"0.10, 3, 0.30", "1250.50, 2, 2501.00", "19.99, 1, 19.99", "0.01, 100, 1.00", "100, 2, 200.00"})
    void lineTotalIsUnitPriceTimesQuantityAtScaleTwo(String price, int qty, String expected) {
        BigDecimal total = OrderTotals.lineTotal(new BigDecimal(price), qty);
        assertThat(total).isEqualTo(new BigDecimal(expected));
        assertThat(total.scale()).isEqualTo(2);
    }

    @Test
    void orderTotalIsTheSumOfLineTotals() {
        OrderTotals totals = OrderTotals.of(List.of(line("0.10", 3), line("19.99", 2), line("1000", 1)));

        assertThat(totals.lineTotals()).containsExactly(
                new BigDecimal("0.30"), new BigDecimal("39.98"), new BigDecimal("1000.00"));
        assertThat(totals.total()).isEqualTo(new BigDecimal("1040.28"));
        assertThat(totals.total().scale()).isEqualTo(2);
    }

    @Test
    void sumIsExactWhereDoubleWouldDrift() {
        OrderTotals totals = OrderTotals.of(List.of(line("0.10", 1), line("0.20", 1)));
        assertThat(totals.total()).isEqualTo(new BigDecimal("0.30"));
    }

    @Test
    void lineTotalsAreAnImmutableSnapshot() {
        OrderTotals totals = OrderTotals.of(List.of(line("1.00", 1)));
        assertThatThrownBy(() -> totals.lineTotals().add(BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyCartIsRejected() {
        assertThatThrownBy(() -> OrderTotals.of(List.of()))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.EMPTY_CART));
    }

    @Test
    void aNullLineIsRejectedWithAClearMessage() {
        assertThatThrownBy(() -> OrderTotals.of(java.util.Arrays.asList(line("1.00", 1), null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lines must not contain null");
    }

    @ParameterizedTest
    @CsvSource({"0", "-1"})
    void nonPositiveQuantityIsRejected(int qty) {
        assertThatThrownBy(() -> OrderTotals.of(List.of(line("1.00", qty))))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.QUANTITY_BELOW_MIN));
    }

    @ParameterizedTest
    @CsvSource({"0", "-0.01", "1.005", "10000000000.00"})
    void invalidUnitPriceIsRejected(String price) {
        assertThatThrownBy(() -> OrderTotals.of(List.of(line(price, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalsThatDoNotFitNumeric12x2AreRejected() {
        BigDecimal max = new BigDecimal("9999999999.99");
        assertThat(OrderTotals.of(List.of(new PricedLine(max, 1))).total()).isEqualTo(max);

        assertThatThrownBy(() -> OrderTotals.of(List.of(new PricedLine(max, 2))))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.ORDER_TOTAL_OUT_OF_RANGE));
        assertThatThrownBy(() -> OrderTotals.of(List.of(
                        new PricedLine(new BigDecimal("6000000000.00"), 1),
                        new PricedLine(new BigDecimal("6000000000.00"), 1))))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.ORDER_TOTAL_OUT_OF_RANGE));
    }
}
