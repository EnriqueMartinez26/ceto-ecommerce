package ar.cetobhb.orders.service;

import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.ShippingZone;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The admin view of one order (AOR-03), built from the stored snapshots. It carries the buyer's name, phone and
 * address (admin only), so its string form hides them. {@code reservedUntil} and {@code secondsLeft} are set only
 * for PAGO_PENDIENTE; {@code secondsLeft} is 0 once the deadline has passed while the order is still payable.
 */
public record OrderDetail(long id, String orderNumber, OrderStatus status, CancelReason cancelReason,
        String buyerName, String buyerPhoneE164, String shippingAddress, ShippingZone zone, BigDecimal total,
        Instant createdAt, Instant updatedAt, Instant paidAt, Instant dispatchedAt, Instant deliveredAt,
        Instant cancelledAt, Instant reservedUntil, Long secondsLeft, List<Line> lines) {

    public OrderDetail {
        lines = List.copyOf(lines);
    }

    /** One snapshot line, as charged at placement. */
    public record Line(int lineNo, long productId, String productName, boolean combo, BigDecimal unitPrice,
            int quantity, int baseUnitsPerUnit, int baseUnitsTotal, BigDecimal lineTotal) {
    }

    @Override
    public String toString() {
        return "OrderDetail[id=" + id + ", number=" + orderNumber + ", status=" + status + "]";
    }
}
