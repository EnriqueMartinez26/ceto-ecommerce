package ar.cetobhb.orders.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * Idempotency fingerprint v1 (AD-7): SHA-256, as 64 lowercase hex characters, over a versioned canonical form
 * of a {@link NormalizedOrderRequest}. The same logical request always yields the same value; any change in
 * name, phone, address, zone, product or quantity yields a different one. The idempotency key and prices are
 * not part of it.
 *
 * <p>Canonical form (UTF-8): the header {@code ceto-order-fp:v1\n}, then one record per field in a fixed order:
 * {@code <label>=<byte length>:<value>\n} for {@code name}, {@code phone}, {@code address}, {@code zone}, then one
 * {@code item} record per line, sorted by product id, whose value is {@code <product id>x<quantity>}. The byte
 * length prefix keeps values containing separators or newlines from colliding with a different split. Changing
 * this form requires a new version header, never a silent edit ({@code OrderRequestFingerprintTest} pins it).
 */
public final class OrderRequestFingerprint {

    static final String HEADER = "ceto-order-fp:v1\n";

    private OrderRequestFingerprint() {}

    public static String of(NormalizedOrderRequest request) {
        StringBuilder canonical = new StringBuilder(HEADER);
        field(canonical, "name", request.name());
        field(canonical, "phone", request.phoneE164());
        field(canonical, "address", request.address());
        field(canonical, "zone", request.zone().name());
        request.lines().stream()
                .sorted(Comparator.comparingLong(PlaceOrderCommand.Line::productId))
                .forEach(line -> field(canonical, "item", line.productId() + "x" + line.quantity()));
        return HexFormat.of().formatHex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void field(StringBuilder out, String label, String value) {
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        out.append(label).append('=').append(byteLength).append(':').append(value).append('\n');
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
