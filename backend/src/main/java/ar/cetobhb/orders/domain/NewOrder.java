package ar.cetobhb.orders.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Everything needed to place an order. The string form hides personal data so it never reaches logs. */
public record NewOrder(
        String orderNumber,
        UUID idempotencyKey,
        String requestFingerprint,
        String buyerName,
        String buyerPhoneE164,
        String shippingAddress,
        ShippingZone zone,
        Instant reservedUntil,
        List<NewOrderLine> lines) {

    public NewOrder {
        lines = List.copyOf(lines);
    }

    @Override
    public String toString() {
        return "NewOrder[number=" + orderNumber + ", key=" + idempotencyKey + ", zone=" + zone + "]";
    }
}
