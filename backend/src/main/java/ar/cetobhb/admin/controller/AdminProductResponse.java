package ar.cetobhb.admin.controller;

import ar.cetobhb.catalog.domain.ProductAdminView;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.common.controller.ApiFormats;

/**
 * One product as the admin sees it (ACAT-01): the editable fields, the combo link, the active flag and the
 * {@code version} the client sends back with an edit. Money is a two-decimal string. A combo has the base product id and
 * the unit count; a base product has {@code null} for both.
 */
record AdminProductResponse(long id, String type, String name, String price, String compareAtPrice, boolean active,
        Long comboBaseProductId, Integer comboBaseUnits, long version) {

    static AdminProductResponse from(ProductAdminView view) {
        return new AdminProductResponse(view.id(), view.isCombo() ? "COMBO" : "BASE", view.name(),
                ApiFormats.money(view.price()), ApiFormats.money(view.compareAtPrice()), view.active(),
                view.comboBaseProductId(), view.comboBaseUnits(), view.version());
    }

    /** A product that was just created: active, at the first version of its row. */
    static AdminProductResponse fromCreated(ProductSnapshot created) {
        return new AdminProductResponse(created.id(), created.isCombo() ? "COMBO" : "BASE", created.name(),
                ApiFormats.money(created.price()), ApiFormats.money(created.compareAtPrice()), true,
                created.comboBaseProductId(), created.comboBaseUnits(), 0);
    }

    /** The product name is not printed (CLAUDE.md section 5: no product or buyer data in logs). */
    @Override
    public String toString() {
        return "AdminProductResponse[id=" + id + ", type=" + type + "]";
    }
}
