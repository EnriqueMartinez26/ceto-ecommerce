package ar.cetobhb.admin.controller;

import ar.cetobhb.admin.domain.AdjustedStock;

/** The counters of a base product after an adjustment; {@code available} is on hand minus reserved. */
record AdjustedStockResponse(long productId, int onHand, int reserved, long available) {

    static AdjustedStockResponse from(AdjustedStock stock) {
        return new AdjustedStockResponse(stock.productId(), stock.onHand(), stock.reserved(), stock.available());
    }
}
