package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The capped admin reads never truncate silently, over HTTP (AINV-01, AINV-06): both caps are 2 here and the REAL queries
 * run; with 3 rows the response carries the first 2 by id and {@code "truncated":true}; with exactly 2, nothing is cut
 * and {@code "truncated":false}.
 */
@TestPropertySource(properties = {"ceto.admin.max-listed-products=2", "ceto.admin.max-reconciliation-rows=2"})
class AdminCappedReadsHttpIT extends AbstractAdminHttpIT {

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JsonMapper json;

    private long baseWithStock(String name) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, 10);
        return id;
    }

    private JsonNode body(String path) throws Exception {
        MockHttpServletResponse response = send(adminGet(path));
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    @Test
    void theStockViewOfThreeProductsAtCapTwoIsTruncatedAndHoldsTheFirstTwoById() throws Exception {
        long first = baseWithStock("Uno");
        long second = baseWithStock("Dos");
        baseWithStock("Tres");

        JsonNode view = body("/api/admin/stock");

        assertThat(view.get("truncated").asBoolean()).isTrue();
        assertThat(view.get("products")).hasSize(2);
        assertThat(view.get("products").get(0).get("productId").asLong()).isEqualTo(first);
        assertThat(view.get("products").get(1).get("productId").asLong()).isEqualTo(second);
    }

    @Test
    void theStockViewOfExactlyTwoProductsAtCapTwoIsNotTruncated() throws Exception {
        baseWithStock("Uno");
        baseWithStock("Dos");

        JsonNode view = body("/api/admin/stock");

        assertThat(view.get("truncated").asBoolean()).isFalse();
        assertThat(view.get("products")).hasSize(2);
    }

    @Test
    void threeMismatchesAtCapTwoAreTruncatedAndTheFirstTwoByIdAreListed() throws Exception {
        long first = baseWithStock("Uno");
        long second = baseWithStock("Dos");
        long third = baseWithStock("Tres");
        for (long id : new long[] {first, second, third}) {
            jdbc.update("UPDATE inventory_stock SET reserved = 1 WHERE product_id = ?", id);
        }

        JsonNode view = body("/api/admin/inventory/reconciliation");

        assertThat(view.get("truncated").asBoolean()).isTrue();
        assertThat(view.get("mismatches")).hasSize(2);
        assertThat(view.get("mismatches").get(0).get("productId").asLong()).isEqualTo(first);
        assertThat(view.get("mismatches").get(1).get("productId").asLong()).isEqualTo(second);
    }

    @Test
    void exactlyTwoMismatchesAtCapTwoAreNotTruncated() throws Exception {
        long first = baseWithStock("Uno");
        long second = baseWithStock("Dos");
        for (long id : new long[] {first, second}) {
            jdbc.update("UPDATE inventory_stock SET reserved = 1 WHERE product_id = ?", id);
        }

        JsonNode view = body("/api/admin/inventory/reconciliation");

        assertThat(view.get("truncated").asBoolean()).isFalse();
        assertThat(view.get("mismatches")).hasSize(2);
    }
}
