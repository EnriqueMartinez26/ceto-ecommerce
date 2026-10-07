package ar.cetobhb.catalog.domain;

import ar.cetobhb.common.domain.DomainException;

/**
 * No visible product with this id. Inactive, hidden (combo over an inactive base) and nonexistent products are the
 * same case on purpose, so the storefront cannot tell them apart.
 */
public class ProductNotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ProductNotFoundException(long productId) {
        super("NOT_FOUND[" + productId + "]");
    }
}
