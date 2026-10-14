package ar.cetobhb.orders.domain;

import java.math.BigDecimal;

/**
 * One priced line of an order about to be placed: the snapshot of the product at checkout time (rule 6)
 * plus how many base units it consumes.
 *
 * <p>Invariants are validated upstream by the request normalizer and the placement service:
 * {@code baseUnitsPerUnit >= 1}, combo consistency (a combo has a different base product and
 * {@code baseUnitsPerUnit >= 2}; a plain product is its own base with 1), unique {@code productId} per order
 * and the maximum number of lines. The first two are also backed by {@code order_items} DB CHECKs; the last two
 * are enforced only upstream. This record does not re-check them.
 */
public record NewOrderLine(
        long productId,
        String productName,
        boolean combo,
        BigDecimal unitPrice,
        int quantity,
        long baseProductId,
        int baseUnitsPerUnit) {

    /** The product name is not printed (CLAUDE.md section 5: no product or buyer data in logs). */
    @Override
    public String toString() {
        return "NewOrderLine[productId=" + productId + ", quantity=" + quantity + "]";
    }
}
