package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static ar.cetobhb.support.CatalogFixtures.productWithCompareAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code GET /api/admin/products} (ACAT-01, ACAT-06): every product by id, active or not, base and combo, with its
 * version for the optimistic lock; an inactive one is here and not in the public list.
 */
class AdminProductListHttpIT extends AbstractAdminHttpIT {

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Test
    void theListMatchesTheGoldenWithInactiveProductsAndCombosOrderedById() throws Exception {
        long frasco = catalog.createBase(productWithCompareAt("Frasco", "100.00", "150.00")).id();
        inventory.createStock(frasco, 10);
        catalog.createCombo(product("Combo x3", "270.00"), frasco, 3);
        long retired = catalog.createBase(product("Retirado", "50.00")).id();
        catalog.setActive(retired, false);

        MockHttpServletResponse response = send(adminGet("/api/admin/products"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        ContractAssertions.assertMatchesGolden(response, "admin-products-list.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(auditRows()).isZero();
    }

    @Test
    void anInactiveProductIsInTheAdminListAndNotInThePublicOne() throws Exception {
        long retired = catalog.createBase(product("Retirado", "50.00")).id();
        inventory.createStock(retired, 3);
        long visible = catalog.createBase(product("Visible", "60.00")).id();
        inventory.createStock(visible, 3);
        catalog.setActive(retired, false);

        String admin = send(adminGet("/api/admin/products")).getContentAsString();
        String storefront = send(get("/api/store/products")).getContentAsString();

        assertThat(admin).contains("Retirado").contains("Visible");
        assertThat(storefront).contains("Visible").doesNotContain("Retirado");
    }

    @Test
    void anEmptyCatalogIsAnEmptyUntruncatedList() throws Exception {
        MockHttpServletResponse response = send(adminGet("/api/admin/products"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"products\":[],\"truncated\":false}");
    }

    @Test
    void withoutASessionIs401AndAnythingButGetOnTheListIs405ForASignedInAdmin() throws Exception {
        MockHttpServletResponse anonymous = send(get("/api/admin/products"));

        assertThat(anonymous.getStatus()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(anonymous, "admin-error-unauthenticated.json",
                "/api/admin/products");
        assertThat(send(adminPutJson("/api/admin/products", "{}")).getStatus()).isEqualTo(405);
    }
}
