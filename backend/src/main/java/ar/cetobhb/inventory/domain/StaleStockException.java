package ar.cetobhb.inventory.domain;

import ar.cetobhb.common.domain.DomainException;

/**
 * An administration stock adjustment was based on an {@code on_hand} that is no longer the committed one (the stock
 * moved, or the same adjustment was already applied once). Compare-and-set under the row lock: nothing changes and no
 * audit row is written. The message carries the product id only, never a count.
 */
public class StaleStockException extends DomainException {

    private static final long serialVersionUID = 1L;

    public StaleStockException(long productId) {
        super("STALE_STOCK(" + productId + ")");
    }
}
