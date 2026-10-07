package ar.cetobhb.orders.domain;

import java.util.List;
import java.util.UUID;

/**
 * Validated checkout request: trimmed text, phone in E.164, parsed key, lines sorted by product id.
 * The string form hides personal data.
 *
 * <p>{@link OrderRequestNormalizer} is the only production producer: it guarantees the invariants (valid text,
 * E.164 phone, lines non-null, with positive ids, unique and sorted by product id). A record's canonical
 * constructor cannot be less accessible than the record, so it stays public; do not build instances elsewhere
 * in production code. The idempotency fingerprint relies on the lines being sorted and unique.
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
