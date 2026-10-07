package ar.cetobhb.catalog.domain;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.math.BigDecimal;

/** Pure catalog rules; the database CHECKs in V1 are the backstop for the same invariants. */
public final class CatalogRules {

    /** Smallest combo: a 1-unit combo is just the base product (D-20261007-08). */
    public static final int MIN_COMBO_UNITS = 2;

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
