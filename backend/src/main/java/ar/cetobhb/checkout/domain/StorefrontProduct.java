package ar.cetobhb.checkout.domain;

import ar.cetobhb.catalog.domain.ProductView;

/**
 * What the storefront shows for one product: the catalog data, a stock state (never a count) and the largest
 * quantity one order line accepts.
 */
public record StorefrontProduct(ProductView product, StockStatus availability, int maxQuantity) {
}
