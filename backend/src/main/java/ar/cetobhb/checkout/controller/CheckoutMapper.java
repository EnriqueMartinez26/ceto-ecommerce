package ar.cetobhb.checkout.controller;

import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates the wire request into the core command. It judges nothing: an unknown zone becomes {@code null} and a
 * missing line becomes {@code null}, so the normalizer reports the business reason (INVALID_ZONE, INVALID_LINE).
 */
final class CheckoutMapper {

    private CheckoutMapper() {
    }

    static PlaceOrderCommand toCommand(String idempotencyKey, PlaceOrderRequest request) {
        BuyerRequest buyer = request.buyer();
        return new PlaceOrderCommand(idempotencyKey,
                buyer == null ? null : new Buyer(buyer.name(), buyer.phone(), buyer.address(), zone(buyer.zone())),
                lines(request.lines()));
    }

    private static ShippingZone zone(String text) {
        if (text == null) {
            return null;
        }
        for (ShippingZone zone : ShippingZone.values()) {
            if (zone.name().equals(text)) {
                return zone;
            }
        }
        return null;
    }

    private static List<PlaceOrderCommand.Line> lines(List<LineRequest> lines) {
        if (lines == null) {
            return null;
        }
        List<PlaceOrderCommand.Line> mapped = new ArrayList<>(lines.size());
        for (LineRequest line : lines) {
            boolean complete = line != null && line.productId() != null && line.quantity() != null;
            mapped.add(complete ? new PlaceOrderCommand.Line(line.productId(), line.quantity()) : null);
        }
        return mapped;
    }
}
