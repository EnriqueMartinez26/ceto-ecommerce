package ar.cetobhb.checkout.controller;

import ar.cetobhb.common.controller.ApiFormats;
import ar.cetobhb.orders.service.PlacedOrder;
import java.util.List;

/**
 * What the buyer gets back. A dedicated record on purpose: the internal order id is never part of the wire contract,
 * and the WhatsApp link is built by the client from these fields (D-20261006-02).
 */
record PlacedOrderResponse(String orderNumber, String status, String zone, String total, String reservedUntil,
        boolean replayed, List<PlacedLineResponse> lines) {

    static PlacedOrderResponse from(PlacedOrder placed) {
        return new PlacedOrderResponse(placed.orderNumber(), placed.status().name(), placed.zone().name(),
                ApiFormats.money(placed.total()), ApiFormats.instant(placed.reservedUntil()), placed.replayed(),
                placed.lines().stream().map(PlacedLineResponse::from).toList());
    }
}
