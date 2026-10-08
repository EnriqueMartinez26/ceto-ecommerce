package ar.cetobhb.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the admin order list (AOR-01, AOR-08): no address, no zone. {@code reservedUntil} is set only while the
 * order is PAGO_PENDIENTE; {@code overdue} is true only for a PAGO_PENDIENTE order whose deadline has passed and the
 * expiry job has not taken yet. The string form hides the buyer's name and phone so a row never reaches a log.
 */
public record OrderSummary(long id, String orderNumber, OrderStatus status, String buyerName, String buyerPhoneE164,
        BigDecimal total, Instant createdAt, Instant reservedUntil, boolean overdue) {

    @Override
    public String toString() {
        return "OrderSummary[id=" + id + ", number=" + orderNumber + ", status=" + status + "]";
    }
}
