package ar.cetobhb.catalog.domain;

import java.math.BigDecimal;

/** Immutable view of a product, including its combo link. A base product resolves to itself with 1 unit. */
public record ProductSnapshot(long id, String name, BigDecimal price, BigDecimal compareAtPrice,
        Long comboBaseProductId, Integer comboBaseUnits) {

    public boolean isCombo() {
        return comboBaseProductId != null;
    }

    public long baseProductId() {
        return isCombo() ? comboBaseProductId : id;
    }

    public int baseUnitsPerUnit() {
        return isCombo() ? comboBaseUnits : 1;
    }
}
