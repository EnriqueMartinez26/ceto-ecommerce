package ar.cetobhb.catalog.domain;

import ar.cetobhb.common.domain.DomainException;

/** An edit was based on a version of the product that is no longer current (a lost update was avoided). */
public class StaleProductVersionException extends DomainException {

    private static final long serialVersionUID = 1L;

    public StaleProductVersionException(long productId) {
        super("STALE_PRODUCT_VERSION(" + productId + ")");
    }
}
