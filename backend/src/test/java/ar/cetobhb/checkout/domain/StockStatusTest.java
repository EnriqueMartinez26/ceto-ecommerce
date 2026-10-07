package ar.cetobhb.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import org.junit.jupiter.api.Test;

class StockStatusTest {

    @Test
    void missingStockRowIsSoldOut() {
        assertThat(StockStatus.of(null, 1)).isEqualTo(StockStatus.SOLD_OUT);
        assertThat(StockStatus.of(null, 3)).isEqualTo(StockStatus.SOLD_OUT);
    }

    @Test
    void baseProductIsAvailableFromOneFreeUnit() {
        assertThat(StockStatus.of(0L, 1)).isEqualTo(StockStatus.SOLD_OUT);
        assertThat(StockStatus.of(1L, 1)).isEqualTo(StockStatus.AVAILABLE);
        assertThat(StockStatus.of(500L, 1)).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void comboIsAvailableOnlyWhenAFullComboFits() {
        assertThat(StockStatus.of(2L, 3)).isEqualTo(StockStatus.SOLD_OUT);
        assertThat(StockStatus.of(3L, 3)).isEqualTo(StockStatus.AVAILABLE);
        assertThat(StockStatus.of(5L, 3)).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void negativeFreeUnitsCannotMakeAProductAvailable() {
        assertThat(StockStatus.of(-4L, 1)).isEqualTo(StockStatus.SOLD_OUT);
    }

    @Test
    void rejectsANonPositiveUnitCount() {
        assertThatThrownBy(() -> StockStatus.of(10L, 0))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).reason()).isEqualTo(ReasonCode.INVALID_COMBO_LINK);
    }
}
