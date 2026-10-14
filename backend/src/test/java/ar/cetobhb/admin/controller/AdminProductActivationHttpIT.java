package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.StockReconciliation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/admin/products/{id}/activate|deactivate} (ACAT-05, ACAT-06): idempotent and version-free
 * (D-20261008-28); a deactivated product leaves the storefront and comes back, while its pending orders stay payable and
 * cancellable, the stock stays reconcilable and a replayed idempotency key still returns the original order.
 */
class AdminProductActivationHttpIT extends AbstractAdminHttpIT {

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JsonMapper json;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long base(String name) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, 10);
        return id;
    }

    private static String path(long id, String action) {
        return "/api/admin/products/" + id + "/" + action;
    }

    private boolean active(long id) {
        return jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, id);
    }

    private boolean onStorefront(long id) throws Exception {
        MockHttpServletResponse detail = send(get("/api/store/products/" + id));
        String list = send(get("/api/store/products")).getContentAsString();
        boolean inList = json.readTree(list).get("products").valueStream()
                .anyMatch(p -> p.get("id").asLong() == id);
        assertThat(detail.getStatus() == 200).as("detail and list agree").isEqualTo(inList);
        return inList;
    }

    @Test
    void deactivatingAnActiveProductIs200ChangedTrueWritesOneAuditRowAndRemovesItFromTheStorefront() throws Exception {
        long id = base("Frasco");
        assertThat(onStorefront(id)).isTrue();

        MockHttpServletResponse response = send(adminPost(path(id, "deactivate")));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(json.readTree(response.getContentAsString()).get("changed").asBoolean()).isTrue();
        assertThat(active(id)).isFalse();
        assertThat(onStorefront(id)).isFalse();
        assertThat(auditActions()).containsExactly("PRODUCT_DEACTIVATED");
    }

    @Test
    void activatingAgainMatchesTheGoldenReappearsOnTheStorefrontAndWritesOneAuditRow() throws Exception {
        long id = base("Frasco");
        send(adminPost(path(id, "deactivate")));

        MockHttpServletResponse response = send(adminPost(path(id, "activate")));

        assertThat(response.getStatus()).isEqualTo(200);
        ContractAssertions.assertMatchesGolden(response, "admin-product-activated.json");
        assertThat(onStorefront(id)).isTrue();
        assertThat(auditActions()).containsExactly("PRODUCT_DEACTIVATED", "PRODUCT_ACTIVATED");
    }

    @Test
    void repeatingAToggleIs200ChangedFalseAndWritesNoAuditRow() throws Exception {
        long id = base("Frasco");
        send(adminPost(path(id, "deactivate")));
        int audit = auditRows();

        MockHttpServletResponse again = send(adminPost(path(id, "deactivate")));
        MockHttpServletResponse alreadyActive = send(adminPost(path(base("Otro"), "activate")));

        assertThat(again.getStatus()).isEqualTo(200);
        ContractAssertions.assertMatchesGolden(again, "admin-product-deactivated-noop.json");
        assertThat(alreadyActive.getStatus()).isEqualTo(200);
        assertThat(json.readTree(alreadyActive.getContentAsString()).get("changed").asBoolean()).isFalse();
        assertThat(auditRows()).isEqualTo(audit);
    }

    @Test
    void payAndCancelOnPendingOrdersOfADeactivatedProductStillWorkAndStockStaysReconcilable() throws Exception {
        long id = base("Frasco");
        long toPay = placement.placeOrder(order(id, 2, "+5493515550101", UUID.randomUUID().toString())).orderId();
        long toCancel = placement.placeOrder(order(id, 3, "+5493515550102", UUID.randomUUID().toString())).orderId();
        send(adminPost(path(id, "deactivate")));

        MockHttpServletResponse paid = send(adminPost("/api/admin/orders/" + toPay + "/pay"));
        MockHttpServletResponse cancelled = send(adminPost("/api/admin/orders/" + toCancel + "/cancel"));

        assertThat(paid.getStatus()).isEqualTo(200);
        assertThat(json.readTree(paid.getContentAsString()).get("status").asString()).isEqualTo("PAGADO");
        assertThat(cancelled.getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT on_hand, reserved FROM inventory_stock WHERE product_id = ?", id))
                .containsEntry("on_hand", 8).containsEntry("reserved", 0);
    }

    @Test
    void replayingAnExistingIdempotencyKeyOfADeactivatedProductStillReturnsTheOriginalOrder() throws Exception {
        long id = base("Frasco");
        String key = UUID.randomUUID().toString();
        String payload = "{\"buyer\":{\"name\":\"Ana Gomez\",\"phone\":\"+5493515550101\",\"address\":\"Calle Falsa 123\","
                + "\"zone\":\"CORDOBA_CAPITAL\"},\"lines\":[{\"productId\":" + id + ",\"quantity\":1}]}";
        MockHttpServletResponse first = send(post("/api/checkout/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(payload.getBytes(StandardCharsets.UTF_8)));
        send(adminPost(path(id, "deactivate")));

        MockHttpServletResponse replay = send(post("/api/checkout/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(payload.getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse fresh = send(post("/api/checkout/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(payload.getBytes(StandardCharsets.UTF_8)));

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(replay.getStatus()).isEqualTo(200);
        JsonNode original = json.readTree(first.getContentAsString());
        assertThat(json.readTree(replay.getContentAsString()).get("orderNumber").asString())
                .isEqualTo(original.get("orderNumber").asString());
        assertThat(fresh.getStatus()).as("a NEW order for a deactivated product is refused").isEqualTo(409);
    }

    @Test
    void activatingAComboWhoseBaseIsInactiveIsRefusedAndTheComboStaysInactive() throws Exception {
        long baseId = base("Frasco");
        long combo = catalog.createCombo(product("Combo x2", "180.00"), baseId, 2).id();
        send(adminPost(path(combo, "deactivate")));
        send(adminPost(path(baseId, "deactivate")));

        MockHttpServletResponse response = send(adminPost(path(combo, "activate")));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(json.readTree(response.getContentAsString()).get("reason").asString())
                .isEqualTo("INVALID_COMBO_LINK");
        assertThat(active(combo)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"activate", "deactivate"})
    void anUnknownIdIs404AndTheIdBoundsAre400(String action) throws Exception {
        MockHttpServletResponse unknown = send(adminPost(path(987654, action)));

        assertThat(unknown.getStatus()).isEqualTo(404);
        ContractAssertions.assertMatchesGoldenForPath(unknown, "admin-error-not-found.json", path(987654, action));
        for (String id : List.of("0", "-1", "9007199254740992", "abc")) {
            assertThat(send(adminPost("/api/admin/products/" + id + "/" + action)).getStatus()).as(id).isEqualTo(400);
        }
        assertThat(auditRows()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"activate", "deactivate"})
    void withoutATokenIs403WithoutASessionIs403AndAGetIs405(String action) throws Exception {
        long id = base("Frasco");

        MockHttpServletResponse noToken = send(post(path(id, action)).with(user("admin")));

        assertThat(noToken.getStatus()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(noToken, "admin-error-forbidden.json", path(id, action));
        assertThat(send(post(path(id, action))).getStatus()).isEqualTo(403);
        assertThat(send(adminGet(path(id, action))).getStatus()).isEqualTo(405);
        assertThat(active(id)).isTrue();
        assertThat(auditRows()).isZero();
    }

    private static PlaceOrderCommand order(long productId, int quantity, String phone, String key) {
        return new PlaceOrderCommand(key,
                new Buyer("Ana Gomez", phone, "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(productId, quantity)));
    }
}
