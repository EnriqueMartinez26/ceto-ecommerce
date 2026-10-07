package ar.cetobhb.catalog.domain;

import java.math.BigDecimal;

/** Data needed to create a product. Descriptive texts are optional free text. */
public record NewProduct(String name, BigDecimal price, BigDecimal compareAtPrice, String presentation,
        String ingredients, String usage) {
}
