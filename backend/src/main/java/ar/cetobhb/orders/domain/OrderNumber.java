package ar.cetobhb.orders.domain;

import java.util.Locale;

/**
 * Human-readable order number (CLAUDE.md section 4, rule 9): {@code CETO-} plus the sequence value
 * zero-padded to six digits. Wider values keep all their digits.
 */
public final class OrderNumber {

    private static final String FORMAT = "CETO-%06d";

    private OrderNumber() {}

    public static String format(long sequenceValue) {
        if (sequenceValue < 1) {
            throw new IllegalArgumentException("order number sequence value must be positive");
        }
        return String.format(Locale.ROOT, FORMAT, sequenceValue);
    }
}
