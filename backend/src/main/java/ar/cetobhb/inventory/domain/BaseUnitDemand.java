package ar.cetobhb.inventory.domain;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Base-unit demand of a cart, aggregated per base product. Backed by a {@link TreeMap} so every
 * iteration is ascending by base product id: this is the lock order (CLAUDE.md section 4, rule 3).
 */
public final class BaseUnitDemand {

    private final TreeMap<Long, Long> unitsByBaseProduct = new TreeMap<>();

    /** Adds {@code quantity} of a line whose product resolves to {@code unitsPerUnit} units of the base product. */
    public void addLine(long baseProductId, int unitsPerUnit, int quantity) {
        if (unitsPerUnit <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("units per unit and quantity must be positive");
        }
        unitsByBaseProduct.merge(baseProductId, Math.multiplyExact((long) unitsPerUnit, quantity), Math::addExact);
    }

    public boolean isEmpty() {
        return unitsByBaseProduct.isEmpty();
    }

    /** Read-only view, ascending by base product id. */
    public Map<Long, Long> asMap() {
        return Collections.unmodifiableMap(unitsByBaseProduct);
    }

    public Set<Long> productIds() {
        return asMap().keySet();
    }
}
