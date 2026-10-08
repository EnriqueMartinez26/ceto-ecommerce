package ar.cetobhb.inventory.domain;

import ar.cetobhb.common.domain.DomainException;

public class StockNotAdjustableException extends DomainException {

    private static final long serialVersionUID = 1L;

    public StockNotAdjustableException(long productId) {
        super("STOCK_NOT_ADJUSTABLE(" + productId + ")");
    }
}
