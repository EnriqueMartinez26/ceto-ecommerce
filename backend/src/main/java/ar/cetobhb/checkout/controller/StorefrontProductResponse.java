package ar.cetobhb.checkout.controller;

import ar.cetobhb.checkout.domain.StorefrontProduct;

/**
 * One list item. A dedicated record on purpose: serializing the domain read model would also emit its public helper
 * methods and the combo's base product id. {@code maxQuantity} is the per-order-line cap, not a stock count.
 */
record StorefrontProductResponse(long id, String name, String kind, Integer comboUnits, String price,
        String compareAtPrice, String availability, int maxQuantity) {

    static StorefrontProductResponse from(StorefrontProduct item) {
        var product = item.product();
        return new StorefrontProductResponse(product.id(), product.name(), product.isCombo() ? "COMBO" : "BASE",
                product.comboBaseUnits(), ApiFormats.money(product.price()),
                ApiFormats.money(product.compareAtPrice()), item.availability().name(), item.maxQuantity());
    }
}
