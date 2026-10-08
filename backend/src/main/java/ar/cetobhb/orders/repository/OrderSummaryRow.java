package ar.cetobhb.orders.repository;

import ar.cetobhb.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the admin order list, read by a constructor projection (no managed entity). It deliberately has no
 * address or zone (AOR-08). The string form hides the buyer's name and phone so a row never reaches a log.
 */
public record OrderSummaryRow(long id, String orderNumber, OrderStatus status, String buyerName,
        String buyerPhoneE164, BigDecimal total, Instant createdAt, Instant reservedUntil) {

    @Override
    public String toString() {
        return "OrderSummaryRow[id=" + id + ", number=" + orderNumber + ", status=" + status + "]";
    }
}
