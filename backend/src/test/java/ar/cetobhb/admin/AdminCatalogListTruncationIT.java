package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.domain.CappedList;
import ar.cetobhb.admin.service.AdminCatalogService;
import ar.cetobhb.catalog.domain.ProductAdminView;
import ar.cetobhb.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Review batch 4, item 9: the admin product list never truncates silently. The cap is 2 and the REAL query runs: with 3
 * products the result is the first 2 by id and {@code truncated = true}; with exactly 2 it is complete.
 */
@TestPropertySource(properties = "ceto.admin.max-listed-products=2")
class AdminCatalogListTruncationIT extends AbstractPostgresIT {

    @Autowired
    private AdminCatalogService admin;

    @Test
    void threeProductsAtCapTwoAreTruncatedAndHoldTheFirstTwoById() {
        long first = admin.createBase(product("Uno", "10.00")).id();
        long second = admin.createBase(product("Dos", "20.00")).id();
        admin.createBase(product("Tres", "30.00"));

        CappedList<ProductAdminView> list = admin.list();

        assertThat(list.truncated()).isTrue();
        assertThat(list.rows()).extracting(ProductAdminView::id).containsExactly(first, second);
    }

    @Test
    void aCountEqualToTheCapIsNotTruncated() {
        long first = admin.createBase(product("Uno", "10.00")).id();
        long second = admin.createBase(product("Dos", "20.00")).id();

        CappedList<ProductAdminView> list = admin.list();

        assertThat(list.truncated()).isFalse();
        assertThat(list.rows()).extracting(ProductAdminView::id).containsExactly(first, second);
    }
}
