package ar.cetobhb.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditEntryTest {

    @Test
    void anOrderEntryCarriesTheOrderAndNothingElse() {
        AuditEntry entry = AuditEntry.order(AuditAction.ORDER_DISPATCHED, 41L);

        assertThat(entry.action()).isEqualTo(AuditAction.ORDER_DISPATCHED);
        assertThat(entry.entityType()).isEqualTo(AuditEntityType.ORDER);
        assertThat(entry.entityId()).isEqualTo(41L);
        assertThat(entry.reason()).isNull();
        assertThat(entry.beforeValue()).isNull();
        assertThat(entry.afterValue()).isNull();
    }

    @Test
    void theOrderFactoryOnlyTakesThePlainOrderActions() {
        for (AuditAction action : AuditAction.values()) {
            boolean plainOrderAction = action == AuditAction.ORDER_PAID || action == AuditAction.ORDER_DISPATCHED
                    || action == AuditAction.ORDER_DELIVERED;
            if (plainOrderAction) {
                assertThat(AuditEntry.order(action, 1L).action()).isEqualTo(action);
            } else {
                assertThatThrownBy(() -> AuditEntry.order(action, 1L)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void aCancellationCarriesTheFixedAdminReason() {
        AuditEntry entry = AuditEntry.orderCancelled(9L);

        assertThat(entry.action()).isEqualTo(AuditAction.ORDER_CANCELLED);
        assertThat(entry.entityType()).isEqualTo(AuditEntityType.ORDER);
        assertThat(entry.reason()).isEqualTo("CANCELLED_BY_ADMIN");
    }

    @Test
    void aStockAdjustmentCarriesTheReasonAndTheBeforeAndAfterCounts() {
        AuditEntry entry = AuditEntry.stockAdjusted(7L, StockAdjustmentReason.DAMAGED_OR_LOST, 25, 20);

        assertThat(entry.action()).isEqualTo(AuditAction.STOCK_ADJUSTED);
        assertThat(entry.entityType()).isEqualTo(AuditEntityType.PRODUCT);
        assertThat(entry.entityId()).isEqualTo(7L);
        assertThat(entry.reason()).isEqualTo("DAMAGED_OR_LOST");
        assertThat(entry.beforeValue()).isEqualByComparingTo("25");
        assertThat(entry.afterValue()).isEqualByComparingTo("20");
    }

    @Test
    void aStockAdjustmentRequiresAReason() {
        assertThatThrownBy(() -> AuditEntry.stockAdjusted(7L, null, 1, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPriceChangeCarriesBothPricesAndNoReason() {
        AuditEntry entry = AuditEntry.priceChanged(7L, new BigDecimal("100.00"), new BigDecimal("150.50"));

        assertThat(entry.action()).isEqualTo(AuditAction.PRICE_CHANGED);
        assertThat(entry.entityType()).isEqualTo(AuditEntityType.PRODUCT);
        assertThat(entry.reason()).isNull();
        assertThat(entry.beforeValue()).isEqualByComparingTo("100.00");
        assertThat(entry.afterValue()).isEqualByComparingTo("150.50");
        assertThatThrownBy(() -> AuditEntry.priceChanged(7L, null, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AuditEntry.priceChanged(7L, BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theProductFactoryOnlyTakesTheValuelessProductActions() {
        List<AuditAction> allowed = List.of(AuditAction.PRODUCT_CREATED, AuditAction.PRODUCT_UPDATED,
                AuditAction.PRODUCT_ACTIVATED, AuditAction.PRODUCT_DEACTIVATED);
        for (AuditAction action : AuditAction.values()) {
            if (allowed.contains(action)) {
                AuditEntry entry = AuditEntry.product(action, 3L);
                assertThat(entry.entityType()).isEqualTo(AuditEntityType.PRODUCT);
                assertThat(entry.reason()).isNull();
                assertThat(entry.beforeValue()).isNull();
            } else {
                assertThatThrownBy(() -> AuditEntry.product(action, 3L))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void anEntityIdBelowOneIsRejectedByEveryFactory() {
        assertThatThrownBy(() -> AuditEntry.order(AuditAction.ORDER_PAID, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEntry.orderCancelled(-1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEntry.stockAdjusted(0L, StockAdjustmentReason.OTHER, 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEntry.priceChanged(0L, BigDecimal.ONE, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEntry.product(AuditAction.PRODUCT_CREATED, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyActionKnowsItsEntityType() {
        for (AuditAction action : AuditAction.values()) {
            AuditEntityType expected = action.name().startsWith("ORDER_") ? AuditEntityType.ORDER
                    : AuditEntityType.PRODUCT;
            assertThat(action.entityType()).as(action.name()).isEqualTo(expected);
        }
    }

    @Test
    void theEntryHasNoRoomForNamesPhonesAddressesOrFreeText() {
        List<String> components = Arrays.stream(AuditEntry.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(components).containsExactly("action", "entityType", "entityId", "reason", "beforeValue",
                "afterValue");
    }
}
