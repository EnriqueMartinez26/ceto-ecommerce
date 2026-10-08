package ar.cetobhb.catalog.domain;

import java.math.BigDecimal;

/**
 * The three editable fields of a product. A full replacement, not a patch: a null {@code compareAtPrice} clears it.
 * The combo link is deliberately absent, so an edit cannot change it.
 */
public record ProductEditData(String name, BigDecimal price, BigDecimal compareAtPrice) {
}
