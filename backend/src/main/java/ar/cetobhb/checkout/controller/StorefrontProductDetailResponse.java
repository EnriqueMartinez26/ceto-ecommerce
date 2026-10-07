package ar.cetobhb.checkout.controller;

import ar.cetobhb.checkout.domain.StorefrontProduct;

/** The detail view: the list item fields plus the client-supplied texts, transported verbatim (nullable). */
record StorefrontProductDetailResponse(long id, String name, String kind, Integer comboUnits, String price,
        String compareAtPrice, String availability, int maxQuantity, String presentation, String ingredients,
        String usage) {

    static StorefrontProductDetailResponse from(StorefrontProduct item) {
        StorefrontProductResponse base = StorefrontProductResponse.from(item);
        var product = item.product();
        return new StorefrontProductDetailResponse(base.id(), base.name(), base.kind(), base.comboUnits(),
                base.price(), base.compareAtPrice(), base.availability(), base.maxQuantity(),
                product.presentation(), product.ingredients(), product.usage());
    }
}
