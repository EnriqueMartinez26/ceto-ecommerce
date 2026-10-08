package ar.cetobhb.catalog.domain;

import java.math.BigDecimal;

/**
 * Admin read model of a product, active or not: the editable fields, the combo link and the optimistic-lock
 * {@code version} a client sends back with an edit. It carries no stock figures.
 */
public record ProductAdminView(long id, String name, BigDecimal price, BigDecimal compareAtPrice,
        Long comboBaseProductId, Integer comboBaseUnits, boolean active, long version) {

    public boolean isCombo() {
        return comboBaseProductId != null;
    }
}
