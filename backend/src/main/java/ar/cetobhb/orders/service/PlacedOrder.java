package ar.cetobhb.orders.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outcome of a successful placement. Carries no buyer data, so it is safe to log.
 *
 * @param replayed true when the idempotency key already belonged to an order with the same request, so the
 *                 existing order is returned and nothing new was created or reserved
 */
public record PlacedOrder(long orderId, String orderNumber, BigDecimal total, Instant reservedUntil,
        boolean replayed) {
}
