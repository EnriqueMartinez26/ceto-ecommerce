package ar.cetobhb.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** INV-A1: a relative stock change that never lets on_hand drop below reserved, whatever the caller sends. */
class InventoryStockAdjustByTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final long ID = 7L;

    /** on_hand 10, reserved 8. */
    private static InventoryStock stock() {
        return stockOf(10, 8);
    }

    private static InventoryStock stockOf(int onHand, int reserved) {
        InventoryStock stock = InventoryStock.create(ID, onHand, NOW);
        if (reserved > 0) {
            stock.reserve(reserved, NOW);
        }
        return stock;
    }

    @Test
    void aNegativeDeltaDownToReservedIsAccepted() {
        InventoryStock stock = stock();

        stock.adjustBy(-2, NOW);

        assertThat(stock.onHand()).isEqualTo(8);
        assertThat(stock.reserved()).isEqualTo(8);
    }

    @Test
    void aDeltaBelowReservedIsRejectedAndLeavesTheStateUntouched() {
        InventoryStock stock = stock();

        assertThatThrownBy(() -> stock.adjustBy(-3, NOW))
                .isInstanceOfSatisfying(InsufficientStockException.class,
                        e -> assertThat(e.productIds()).containsExactly(ID));

        assertThat(stock.onHand()).isEqualTo(10);
        assertThat(stock.reserved()).isEqualTo(8);
    }

    @Test
    void aPositiveDeltaRestocks() {
        InventoryStock stock = stock();

        stock.adjustBy(5, NOW);

        assertThat(stock.onHand()).isEqualTo(15);
        assertThat(stock.reserved()).isEqualTo(8);
    }

    @Test
    void aDeltaOfZeroIsAValidationError() {
        InventoryStock stock = stock();

        assertThatThrownBy(() -> stock.adjustBy(0, NOW))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_STOCK_DELTA));

        assertThat(stock.onHand()).isEqualTo(10);
    }

    @Test
    void aResultAboveTheIntegerRangeIsAValidationError() {
        InventoryStock stock = stockOf(Integer.MAX_VALUE - 1, 0);

        assertThatThrownBy(() -> stock.adjustBy(2, NOW))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.STOCK_OUT_OF_RANGE));
        assertThatThrownBy(() -> stockOf(10, 0).adjustBy(Integer.MAX_VALUE, NOW))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.STOCK_OUT_OF_RANGE));

        assertThat(stock.onHand()).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    void theExactIntegerMaximumIsReachable() {
        InventoryStock stock = stockOf(Integer.MAX_VALUE - 1, 0);

        stock.adjustBy(1, NOW);

        assertThat(stock.onHand()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aNegativeResultIsRejectedAsInsufficientStock() {
        InventoryStock stock = stockOf(10, 0);

        assertThatThrownBy(() -> stock.adjustBy(-11, NOW)).isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> stock.adjustBy(Integer.MIN_VALUE, NOW)).isInstanceOf(InsufficientStockException.class);

        assertThat(stock.onHand()).isEqualTo(10);
    }

    @Test
    void takingEverythingThatIsFreeLeavesExactlyTheReservedUnits() {
        InventoryStock stock = stockOf(10, 4);

        stock.adjustBy(-6, NOW);

        assertThat(stock.onHand()).isEqualTo(4);
        assertThat(stock.reserved()).isEqualTo(4);
        assertThat(stock.free()).isZero();
    }
}
