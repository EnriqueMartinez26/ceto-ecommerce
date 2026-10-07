package ar.cetobhb.orders.domain;

import java.util.List;

/**
 * Raw checkout request. It carries ids and quantities only: prices and totals are computed by the server
 * (CLAUDE.md rule 7).
 *
 * @param idempotencyKey key sent by the client for this attempt, as text
 * @param buyer          buyer data
 * @param lines          requested products
 */
public record PlaceOrderCommand(String idempotencyKey, Buyer buyer, List<Line> lines) {

    /** One requested product and its quantity. */
    public record Line(long productId, int quantity) {}
}
