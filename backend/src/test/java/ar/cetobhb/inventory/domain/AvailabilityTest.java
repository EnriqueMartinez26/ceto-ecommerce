package ar.cetobhb.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AvailabilityTest {

    @ParameterizedTest(name = "combo on_hand={0} reserved={1} N={2} -> {3}")
    @CsvSource({
        "10, 0, 3, 3",
        "10, 1, 3, 3",
        "10, 2, 3, 2",
        "2, 0, 3, 0",
        "5, 5, 1, 0",
        "5, 0, 1, 5",
        "9, 0, 3, 3",
        "0, 0, 2, 0"
    })
    void comboAvailabilityIsFloorOfFreeUnitsDividedByN(long onHand, long reserved, int n, long expected) {
        assertThat(Availability.forCombo(onHand, reserved, n)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "base on_hand={0} reserved={1} -> {2}")
    @CsvSource({"10, 0, 10", "10, 4, 6", "5, 5, 0", "0, 0, 0"})
    void baseAvailabilityIsOnHandMinusReserved(long onHand, long reserved, long expected) {
        assertThat(Availability.forBase(onHand, reserved)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "combo N={0} is rejected")
    @CsvSource({"0", "-1", "-5"})
    void nonPositiveComboUnitsAreRejected(int n) {
        assertThatThrownBy(() -> Availability.forCombo(10, 0, n))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_COMBO_LINK));
    }

    @ParameterizedTest(name = "never negative: on_hand={0} reserved={1}")
    @CsvSource({"3, 4", "0, 1", "0, 100"})
    void availabilityNeverNegativeEvenIfStockIsInconsistent(long onHand, long reserved) {
        assertThat(Availability.forBase(onHand, reserved)).isZero();
        assertThat(Availability.forCombo(onHand, reserved, 3)).isZero();
    }
}
