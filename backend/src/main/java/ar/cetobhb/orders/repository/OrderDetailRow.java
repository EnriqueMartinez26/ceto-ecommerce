package ar.cetobhb.orders.repository;

import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.ShippingZone;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Header of one order for the admin detail, read by a constructor projection so that no managed {@code Order} is
 * left in the persistence context. It holds the buyer's name, phone and address, so the string form hides them.
 */
public record OrderDetailRow(long id, String orderNumber, OrderStatus status, CancelReason cancelReason,
        String buyerName, String buyerPhoneE164, String shippingAddress, ShippingZone zone, BigDecimal total,
        Instant createdAt, Instant updatedAt, Instant paidAt, Instant dispatchedAt, Instant deliveredAt,
        Instant cancelledAt, Instant reservedUntil) {

    @Override
    public String toString() {
        return "OrderDetailRow[id=" + id + ", number=" + orderNumber + ", status=" + status + "]";
    }
}
