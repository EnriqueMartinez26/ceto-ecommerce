package ar.cetobhb.catalog.domain;

import java.math.BigDecimal;

/**
 * Read model of a product for display: the {@link ProductSnapshot} data plus the client-supplied texts. A base
 * product resolves to itself with 1 unit.
 */
public record ProductView(long id, String name, BigDecimal price, BigDecimal compareAtPrice,
        Long comboBaseProductId, Integer comboBaseUnits, String presentation, String ingredients, String usage) {

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
