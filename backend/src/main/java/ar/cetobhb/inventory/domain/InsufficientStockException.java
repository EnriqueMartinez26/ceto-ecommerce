package ar.cetobhb.inventory.domain;

import ar.cetobhb.common.domain.DomainException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** One or more base products lack the requested free units. Carries ids only. */
public class InsufficientStockException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final TreeSet<Long> productIds;

    public InsufficientStockException(Set<Long> productIds) {
        super("INSUFFICIENT_STOCK" + new TreeSet<>(productIds));
        this.productIds = new TreeSet<>(productIds);
    }

    public Set<Long> productIds() {
        return Collections.unmodifiableSet(productIds);
    }
}
