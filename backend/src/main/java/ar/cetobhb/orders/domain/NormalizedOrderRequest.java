package ar.cetobhb.orders.domain;

import java.util.List;
import java.util.UUID;

/**
 * Validated checkout request: trimmed text, phone in E.164, parsed key, lines sorted by product id.
 * The string form hides personal data.
 */
public record NormalizedOrderRequest(
        UUID idempotencyKey,
        String name,
        String phoneE164,
        String address,
        ShippingZone zone,
        List<PlaceOrderCommand.Line> lines) {

    public NormalizedOrderRequest {
        lines = List.copyOf(lines);
    }

    @Override
    public String toString() {
        return "NormalizedOrderRequest[key=" + idempotencyKey + ", zone=" + zone + ", lines=" + lines + "]";
    }
}
