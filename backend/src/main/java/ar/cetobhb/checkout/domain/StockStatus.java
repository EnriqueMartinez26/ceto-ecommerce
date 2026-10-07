package ar.cetobhb.checkout.domain;

import ar.cetobhb.inventory.domain.Availability;

/**
 * What the storefront says about stock: a state, never a number (D-20261007-20, pending the client's OK). The enum is
 * extensible and clients must tolerate unknown values.
 */
public enum StockStatus {
    AVAILABLE,
    SOLD_OUT;

    /**
     * @param freeBaseUnits base units free to reserve, or null when the base product has no stock row (never an error
     *                      on the read path)
     * @param unitsPerUnit  base units one sold unit consumes (1 for a base product, N for a combo)
     */
    public static StockStatus of(Long freeBaseUnits, int unitsPerUnit) {
        if (freeBaseUnits == null) {
            return SOLD_OUT;
        }
        return Availability.forCombo(freeBaseUnits, 0, unitsPerUnit) >= 1 ? AVAILABLE : SOLD_OUT;
    }
}
