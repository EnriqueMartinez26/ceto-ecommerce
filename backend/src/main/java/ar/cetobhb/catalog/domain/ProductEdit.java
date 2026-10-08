package ar.cetobhb.catalog.domain;

import java.math.BigDecimal;

/**
 * What an edit of a product changed, by group: the price (audited as PRICE_CHANGED) and everything else, the name
 * and the compare-at price (audited as PRODUCT_UPDATED). Prices are the stored values (scale 2).
 */
public record ProductEdit(BigDecimal priceBefore, BigDecimal priceAfter, boolean otherChanged) {

    public boolean priceChanged() {
        return priceBefore.compareTo(priceAfter) != 0;
    }

    public boolean changedAnything() {
        return priceChanged() || otherChanged;
    }
}
