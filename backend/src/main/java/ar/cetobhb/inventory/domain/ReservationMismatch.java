package ar.cetobhb.inventory.domain;

/**
 * One base product whose {@code reserved} counter differs from the base units of its pending order lines.
 * {@code reserved} is null when the product has pending demand but no stock row at all.
 */
public record ReservationMismatch(long productId, Integer reserved, long expected) {
}
