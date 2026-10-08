package ar.cetobhb.admin.domain;

import ar.cetobhb.inventory.domain.Availability;

/**
 * Stock counters of one base product after an admin adjustment. {@code available} is derived (on_hand minus reserved,
 * never negative), not stored.
 */
public record AdjustedStock(long productId, int onHand, int reserved, long available) {

    public static AdjustedStock of(long productId, int onHand, int reserved) {
        return new AdjustedStock(productId, onHand, reserved, Availability.forBase(onHand, reserved));
    }
}
