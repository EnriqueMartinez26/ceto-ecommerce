package ar.cetobhb.inventory.domain;

import ar.cetobhb.common.domain.DomainException;

/** A base product has no stock row: fail closed, never assume zero or infinite stock. */
public class StockNotConfiguredException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final long productId;

    public StockNotConfiguredException(long productId) {
        super("STOCK_NOT_CONFIGURED(" + productId + ")");
        this.productId = productId;
    }

    public long productId() {
        return productId;
    }
}
