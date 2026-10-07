package ar.cetobhb.checkout;

import static ar.cetobhb.support.ContractAssertions.assertMatchesGolden;
import static ar.cetobhb.support.ContractAssertions.stripRequestId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractHttpIT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Storefront HTTP contract (REQ-STO-01..03): exact JSON shape, formats, id bounds and a neutral, identical 404. */
class StorefrontHttpIT extends AbstractHttpIT {

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JsonMapper json;

    private void seedCatalog() {
        long shampoo = catalog.createBase(new NewProduct("Solid shampoo", new BigDecimal("45000"),
                new BigDecimal("50000"), "100 g bar", "Coconut oil, shea butter", "Lather on wet hair")).id();
        inventory.createStock(shampoo, 10);
        catalog.createCombo(new NewProduct("Shampoo pack", new BigDecimal("120000.5"), null, null, null, null),
                shampoo, 3);
        catalog.createBase(new NewProduct("Cream", new BigDecimal("100"), null, null, null, null));
    }

    @Test
    void listMatchesTheGoldenFile() throws Exception {
        seedCatalog();

        MockHttpServletResponse response = send(get("/api/store/products"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        assertMatchesGolden(response, "storefront-list.json");
    }

    @Test
    void detailMatchesTheGoldenFile() throws Exception {
        seedCatalog();

        MockHttpServletResponse response = send(get("/api/store/products/1"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertMatchesGolden(response, "storefront-detail.json");
    }

    @Test
    void noStockOrInternalFieldEverAppears() throws Exception {
        seedCatalog();

        String list = send(get("/api/store/products")).getContentAsString(StandardCharsets.UTF_8);
        String detail = send(get("/api/store/products/2")).getContentAsString(StandardCharsets.UTF_8);

        for (String body : List.of(list, detail)) {
            assertThat(body).doesNotContain("onHand", "reserved", "available\"", "stock", "quantity\"",
                    "comboBaseProductId", "active", "maxPerOrder", "combo\"");
        }
    }

    @Test
    void inactiveProductsAndCombosOverInactiveBasesAreHidden() throws Exception {
        long base = catalog.createBase(new NewProduct("Base", new BigDecimal("10"), null, null, null, null)).id();
        inventory.createStock(base, 5);
        long combo = catalog.createCombo(new NewProduct("Combo", new BigDecimal("30"), null, null, null, null),
                base, 3).id();
        long visible = catalog.createBase(new NewProduct("Visible", new BigDecimal("5"), null, null, null, null))
                .id();
        catalog.deactivate(base);

        JsonNode list = json.readTree(send(get("/api/store/products")).getContentAsString(StandardCharsets.UTF_8));

        assertThat(list.get("products")).hasSize(1);
        assertThat(list.get("products").get(0).get("id").asLong()).isEqualTo(visible);
        assertThat(send(get("/api/store/products/" + combo)).getStatus()).isEqualTo(404);
    }

    @Test
    void notFoundBodiesAreByteIdenticalForInactiveHiddenAndUnknownIds() throws Exception {
        long base = catalog.createBase(new NewProduct("Base", new BigDecimal("10"), null, null, null, null)).id();
        long combo = catalog.createCombo(new NewProduct("Combo", new BigDecimal("30"), null, null, null, null),
                base, 3).id();
        long inactive = catalog.createBase(new NewProduct("Off", new BigDecimal("5"), null, null, null, null)).id();
        catalog.deactivate(inactive);
        catalog.deactivate(base);

        String hidden = notFoundBody(combo);
        String off = notFoundBody(inactive);
        String unknown = notFoundBody(999_999L);

        // Same path shape, so even `instance` can only differ by the id digits; normalize it.
        assertThat(normalize(off, inactive)).isEqualTo(normalize(unknown, 999_999L));
        assertThat(normalize(hidden, combo)).isEqualTo(normalize(unknown, 999_999L));
        JsonNode body = json.readTree(unknown);
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(body.has("type")).isFalse();
        assertThat(body.get("detail").asString()).doesNotContain("999999", "NOT_FOUND[");
    }

    private String notFoundBody(long id) throws Exception {
        MockHttpServletResponse response = send(get("/api/store/products/" + id));
        assertThat(response.getStatus()).isEqualTo(404);
        return stripRequestId(response);
    }

    private static String normalize(String body, long id) {
        return body.replace("/api/store/products/" + id, "/api/store/products/{id}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9007199254740992", "abc", "99999999999999999999"})
    void idsOutsideTheBoundsAre400ValidationErrors(String id) throws Exception {
        MockHttpServletResponse response = send(get("/api/store/products/" + id));

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = json.readTree(stripRequestId(response));
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.has("type")).isFalse();
        assertThat(body.has("reason")).isFalse();
    }

    @Test
    void theLargestAllowedIdIsAValidLookupThatSimplyDoesNotExist() throws Exception {
        assertThat(send(get("/api/store/products/9007199254740991")).getStatus()).isEqualTo(404);
    }
}
