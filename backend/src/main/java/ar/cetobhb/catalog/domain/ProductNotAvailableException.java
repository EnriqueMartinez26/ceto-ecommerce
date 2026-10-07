package ar.cetobhb.catalog.domain;

import ar.cetobhb.common.domain.DomainException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** One or more requested products are missing or inactive. */
public class ProductNotAvailableException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final TreeSet<Long> productIds;

    public ProductNotAvailableException(Set<Long> productIds) {
        super("PRODUCT_UNAVAILABLE" + new TreeSet<>(productIds));
        this.productIds = new TreeSet<>(productIds);
    }

    public Set<Long> productIds() {
        return Collections.unmodifiableSet(productIds);
    }
}
