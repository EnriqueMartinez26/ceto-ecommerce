package ar.cetobhb.orders.service;

import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.ShippingZone;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Outcome of a successful placement, read from the persisted order (never from the current catalog). Carries no
 * buyer data, so it is safe to log.
 *
 * @param status   the order's CURRENT status: a replay of a cancelled order reports CANCELADO
 * @param lines    the snapshot lines ordered by product id, with the prices charged at placement
 * @param replayed true when the idempotency key already belonged to an order with the same request, so the
 *                 existing order is returned and nothing new was created or reserved
 */
public record PlacedOrder(long orderId, String orderNumber, OrderStatus status, ShippingZone zone, BigDecimal total,
        Instant reservedUntil, boolean replayed, List<PlacedLine> lines) {

    public PlacedOrder {
        lines = List.copyOf(lines);
    }

    /** One snapshot line: what was ordered and the unit price and line total charged at placement. */
    public record PlacedLine(long productId, String productName, int quantity, BigDecimal unitPrice,
            BigDecimal lineTotal) {

        @Override
        public String toString() {
            return "PlacedLine[productId=" + productId + ", quantity=" + quantity + "]";
        }
    }
}
