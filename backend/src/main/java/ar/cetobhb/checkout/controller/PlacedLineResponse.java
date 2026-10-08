package ar.cetobhb.checkout.controller;

import ar.cetobhb.orders.service.PlacedOrder.PlacedLine;

/** One snapshot line of a placed order: the prices charged at placement, as decimal strings. */
record PlacedLineResponse(long productId, String name, int quantity, String unitPrice, String lineTotal) {

    static PlacedLineResponse from(PlacedLine line) {
        return new PlacedLineResponse(line.productId(), line.productName(), line.quantity(),
                ApiFormats.money(line.unitPrice()), ApiFormats.money(line.lineTotal()));
    }
}
