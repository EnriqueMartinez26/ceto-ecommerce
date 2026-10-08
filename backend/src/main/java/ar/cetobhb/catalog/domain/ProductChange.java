package ar.cetobhb.catalog.domain;

import java.math.BigDecimal;

/**
 * Result of an admin edit: the price before and after (stored values), which field groups changed, and the product as
 * it is after the edit, with its new version. The caller audits {@code priceChanged} and {@code otherChanged}.
 */
public record ProductChange(BigDecimal priceBefore, BigDecimal priceAfter, boolean priceChanged,
        boolean otherChanged, ProductAdminView view) {
}
