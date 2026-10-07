package ar.cetobhb.orders.service;

import java.math.BigDecimal;
import java.time.Instant;

/** Outcome of a successful placement. Carries no buyer data, so it is safe to log. */
public record PlacedOrder(long orderId, String orderNumber, BigDecimal total, Instant reservedUntil) {
}
