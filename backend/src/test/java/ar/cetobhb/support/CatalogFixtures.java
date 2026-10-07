package ar.cetobhb.support;

import ar.cetobhb.catalog.domain.NewProduct;
import java.math.BigDecimal;

/** Test-only product data. Nothing here may ever reach production (CLAUDE.md section 6). */
public final class CatalogFixtures {

    private CatalogFixtures() {
    }

    public static NewProduct product(String name, String price) {
        return new NewProduct(name, new BigDecimal(price), null, null, null, null);
    }

    public static NewProduct productWithCompareAt(String name, String price, String compareAt) {
        return new NewProduct(name, new BigDecimal(price), new BigDecimal(compareAt), null, null, null);
    }
}
