package ar.cetobhb.inventory.domain;

import ar.cetobhb.common.domain.DomainException;

/** A base product already has a stock row; creating it again (including a concurrent duplicate) is refused. */
public class StockAlreadyConfiguredException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final long productId;

    public StockAlreadyConfiguredException(long productId) {
        super("STOCK_ALREADY_CONFIGURED(" + productId + ")");
        this.productId = productId;
    }

    public long productId() {
        return productId;
    }
}
