package ar.cetobhb.catalog.domain;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.math.BigDecimal;

/** Pure catalog rules; the database CHECKs in V1 are the backstop for the same invariants. */
public final class CatalogRules {

    /** Smallest combo: a 1-unit combo is just the base product (D-20261007-08). */
    public static final int MIN_COMBO_UNITS = 2;

    private static final int MAX_NAME_LENGTH = 200;

    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private CatalogRules() {
    }

    /** price > 0, at most 2 decimals (never rounded), fits NUMERIC(12,2); compareAt, when present, is > price. */
    public static void requireValidPrices(BigDecimal price, BigDecimal compareAtPrice) {
        requireMoney(price);
        if (compareAtPrice != null) {
            requireMoney(compareAtPrice);
            if (compareAtPrice.compareTo(price) <= 0) {
                throw new ValidationException(ReasonCode.INVALID_PRICE_PAIR);
            }
        }
    }

    /**
     * Required, trimmed length 1-200 (the column is VARCHAR(200)), with no control, format or unpaired surrogate
     * characters, line or paragraph separators, private-use or unassigned code points ({@code INVALID_CHARACTERS}),
     * and at least one visible code point (a name of only blanks, NBSP or fillers is {@code INVALID_PRODUCT_NAME}).
     * The text is validated, never rewritten or escaped: output escaping is
     * the renderer's job.
     */
    public static void requireName(String name) {
        if (name == null) {
            throw new ValidationException(ReasonCode.INVALID_PRODUCT_NAME);
        }
        String trimmed = name.strip();
        int length = trimmed.length();
        if (length < 1 || length > MAX_NAME_LENGTH) {
            throw new ValidationException(ReasonCode.INVALID_PRODUCT_NAME);
        }
        if (trimmed.codePoints().anyMatch(CatalogRules::isForbiddenCharacter)) {
            throw new ValidationException(ReasonCode.INVALID_CHARACTERS);
        }
        if (trimmed.codePoints().noneMatch(CatalogRules::isVisible)) {
            throw new ValidationException(ReasonCode.INVALID_PRODUCT_NAME);
        }
    }

    private static boolean isForbiddenCharacter(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR, Character.PRIVATE_USE, Character.UNASSIGNED -> true;
            default -> false;
        };
    }

    /**
     * A code point a person can see: not white space (Java's {@code isWhitespace} leaves NBSP out, so
     * {@code isSpaceChar} is checked too) and not one of the letters and symbols that render as blank (Hangul fillers
     * U+115F, U+1160, U+3164 and U+FFA0, and the Braille blank U+2800).
     */
    private static boolean isVisible(int codePoint) {
        return !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                && codePoint != 0x115F && codePoint != 0x1160 && codePoint != 0x3164
                && codePoint != 0xFFA0 && codePoint != 0x2800;
    }

    public static void requireComboUnits(int units) {
        if (units < MIN_COMBO_UNITS) {
            throw new ValidationException(ReasonCode.INVALID_COMBO_LINK);
        }
    }

    private static void requireMoney(BigDecimal value) {
        if (value == null || value.signum() <= 0
                || value.stripTrailingZeros().scale() > 2
                || value.compareTo(MAX_PRICE) > 0) {
            throw new ValidationException(ReasonCode.INVALID_PRICE_PAIR);
        }
    }
}
