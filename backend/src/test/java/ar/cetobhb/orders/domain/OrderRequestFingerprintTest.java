package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OrderRequestFingerprintTest {

    /** Pinned canonical v1 value: changing the canonical form must fail here, not silently in production. */
    private static final String GOLDEN = "bcea57965c6e54625d344a1a4e79b72cac540d9d6a360f906b1eebefb86b9fc5";

    private static final String KEY = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b";
    private static final Buyer BUYER = new Buyer("Maria Perez", "+54 9 351 555 0000", "Calle Falsa 123",
            ShippingZone.CORDOBA_CAPITAL);

    private final OrderRequestNormalizer normalizer =
            new OrderRequestNormalizer(new PhoneNumberNormalizer(), new OrderLimits(10, 20));

    private static PlaceOrderCommand.Line line(long id, int qty) {
        return new PlaceOrderCommand.Line(id, qty);
    }

    private String fingerprint(String key, Buyer buyer, PlaceOrderCommand.Line... lines) {
        return OrderRequestFingerprint.of(normalizer.normalize(new PlaceOrderCommand(key, buyer, List.of(lines))));
    }

    private String base(PlaceOrderCommand.Line... lines) {
        return fingerprint(KEY, BUYER, lines);
    }

    /**
     * Renaming a {@link ShippingZone} constant (or any canonical label) changes the fingerprint of in-flight
     * idempotency keys: it requires a version bump ({@code v2}) and a golden update, never a silent edit.
     */
    @Test
    void goldenValuePinsTheCanonicalV1Form() {
        assertThat(base(line(3, 1), line(7, 2))).isEqualTo(GOLDEN);
    }

    @Test
    void outputIsSixtyFourLowercaseHexCharacters() {
        assertThat(base(line(1, 1))).matches(Pattern.compile("[0-9a-f]{64}"));
    }

    @Test
    void lineOrderDoesNotMatter() {
        assertThat(base(line(7, 2), line(3, 1))).isEqualTo(base(line(3, 1), line(7, 2)));
    }

    @Test
    void phoneFormattingAndSurroundingWhitespaceDoNotMatter() {
        Buyer variant = new Buyer("  Maria   Perez ", "0351 15-555-0000", "Calle Falsa\t123 ",
                ShippingZone.CORDOBA_CAPITAL);
        assertThat(fingerprint(KEY, variant, line(3, 1), line(7, 2))).isEqualTo(base(line(3, 1), line(7, 2)));
    }

    @Test
    void theIdempotencyKeyIsExcluded() {
        String other = "aaaaaaaa-5d4a-4e6f-9a7b-1c2d3e4f5a6b";
        assertThat(fingerprint(other, BUYER, line(1, 1))).isEqualTo(base(line(1, 1)));
    }

    @Test
    void anyChangeInTheRequestChangesTheFingerprint() {
        String reference = base(line(3, 1), line(7, 2));
        assertThat(fingerprint(KEY, new Buyer("Maria Perez2", BUYER.phone(), BUYER.address(), BUYER.zone()),
                line(3, 1), line(7, 2))).isNotEqualTo(reference);
        assertThat(fingerprint(KEY, new Buyer(BUYER.name(), "+54 9 351 555 0001", BUYER.address(), BUYER.zone()),
                line(3, 1), line(7, 2))).isNotEqualTo(reference);
        assertThat(fingerprint(KEY, new Buyer(BUYER.name(), BUYER.phone(), "Calle Falsa 124", BUYER.zone()),
                line(3, 1), line(7, 2))).isNotEqualTo(reference);
        assertThat(fingerprint(KEY, new Buyer(BUYER.name(), BUYER.phone(), BUYER.address(),
                ShippingZone.RESTO_DEL_PAIS), line(3, 1), line(7, 2))).isNotEqualTo(reference);
        assertThat(base(line(3, 1), line(7, 3))).isNotEqualTo(reference);
        assertThat(base(line(3, 1), line(8, 2))).isNotEqualTo(reference);
        assertThat(base(line(3, 1))).isNotEqualTo(reference);
    }

    @Test
    void lengthPrefixesDefeatDelimiterAmbiguity() {
        NormalizedOrderRequest a = new NormalizedOrderRequest(UUID.fromString(KEY), "a\nb", "+5493515550000", "c",
                ShippingZone.CORDOBA_CAPITAL, List.of(line(1, 1)));
        NormalizedOrderRequest b = new NormalizedOrderRequest(UUID.fromString(KEY), "a", "+5493515550000", "b\nc",
                ShippingZone.CORDOBA_CAPITAL, List.of(line(1, 1)));
        assertThat(OrderRequestFingerprint.of(a)).isNotEqualTo(OrderRequestFingerprint.of(b));
    }

    @Test
    void multibyteTextIsMeasuredInUtf8Bytes() {
        NormalizedOrderRequest a = new NormalizedOrderRequest(UUID.fromString(KEY), "é", "+5493515550000", "x",
                ShippingZone.CORDOBA_CAPITAL, List.of(line(1, 1)));
        NormalizedOrderRequest b = new NormalizedOrderRequest(UUID.fromString(KEY), "e", "+5493515550000", "x",
                ShippingZone.CORDOBA_CAPITAL, List.of(line(1, 1)));
        assertThat(OrderRequestFingerprint.of(a)).isNotEqualTo(OrderRequestFingerprint.of(b));
    }
}
