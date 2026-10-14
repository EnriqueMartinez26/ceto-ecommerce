package ar.cetobhb.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.ContractAssertions;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PUT /api/admin/products/{id}} (ACAT-04, ACAT-06): name, price and offer price only, as a FULL replacement (an
 * omitted {@code compareAtPrice} clears the offer, D-20261008-21), guarded by the version the client read; the combo shape
 * cannot be sent; a past order keeps its price.
 */
class AdminProductUpdateHttpIT extends AbstractAdminHttpIT {

    private static final String CREATE =
            "{\"type\":\"BASE\",\"name\":\"Frasco\",\"price\":\"100.00\",\"compareAtPrice\":\"150.00\"}";

    @Autowired
    private JsonMapper json;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    /** Creates a base product over HTTP (version 0, offer price 150.00) and returns its id. */
    private long created() throws Exception {
        MockHttpServletResponse response = send(adminPostJson("/api/admin/products", CREATE));
        assertThat(response.getStatus()).isEqualTo(201);
        return json.readTree(response.getContentAsString()).get("id").asLong();
    }

    private static String path(long id) {
        return "/api/admin/products/" + id;
    }

    private JsonNode row(long id) {
        return json.valueToTree(jdbc.queryForMap(
                "SELECT name, price::text AS price, compare_at_price::text AS compare, version FROM products WHERE id = ?",
                id));
    }

    @Test
    void anEditReturnsTheNewViewWithTheNextVersionAndOmittingTheOfferPriceClearsIt() throws Exception {
        long id = created();

        MockHttpServletResponse response = send(adminPutJson(path(id),
                "{\"name\":\"Frasco 60\",\"price\":\"120.00\",\"version\":0}"));

        assertThat(response.getStatus()).isEqualTo(200);
        ContractAssertions.assertMatchesGolden(response, "admin-product-updated.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(jdbc.queryForObject("SELECT compare_at_price FROM products WHERE id = ?", BigDecimal.class, id))
                .as("a full replacement: the omitted offer price is stored as null").isNull();
        assertThat(auditActions()).containsExactly("PRODUCT_CREATED", "PRICE_CHANGED", "PRODUCT_UPDATED");
        assertThat(jdbc.queryForMap("SELECT before_value, after_value FROM admin_audit_log WHERE action = 'PRICE_CHANGED'"))
                .containsEntry("before_value", new BigDecimal("100.00"))
                .containsEntry("after_value", new BigDecimal("120.00"));
    }

    @Test
    void anExplicitNullClearsTheOfferTooAndAnExplicitValueSetsIt() throws Exception {
        long id = created();

        JsonNode cleared = json.readTree(send(adminPutJson(path(id),
                "{\"name\":\"Frasco\",\"price\":\"100.00\",\"compareAtPrice\":null,\"version\":0}")).getContentAsString());
        JsonNode set = json.readTree(send(adminPutJson(path(id),
                "{\"name\":\"Frasco\",\"price\":\"100.00\",\"compareAtPrice\":\"130.00\",\"version\":1}"))
                .getContentAsString());

        assertThat(cleared.get("compareAtPrice").isNull()).isTrue();
        assertThat(cleared.get("version").asLong()).isEqualTo(1);
        assertThat(set.get("compareAtPrice").asString()).isEqualTo("130.00");
        assertThat(set.get("version").asLong()).isEqualTo(2);
    }

    @Test
    void anEditThatChangesNothingIs200WritesNoAuditRowAndDoesNotBumpTheVersion() throws Exception {
        long id = created();
        int before = auditRows();

        JsonNode same = json.readTree(send(adminPutJson(path(id),
                "{\"name\":\"Frasco\",\"price\":\"100.00\",\"compareAtPrice\":\"150.00\",\"version\":0}"))
                .getContentAsString());

        assertThat(same.get("version").asLong()).isZero();
        assertThat(auditRows()).isEqualTo(before);
    }

    @Test
    void aStaleVersionIs409ConflictAndTheProductIsUnchanged() throws Exception {
        long id = created();
        assertThat(send(adminPutJson(path(id), "{\"name\":\"A\",\"price\":\"110.00\",\"version\":0}")).getStatus())
                .isEqualTo(200);
        int audit = auditRows();

        MockHttpServletResponse stale = send(adminPutJson(path(id), "{\"name\":\"B\",\"price\":\"999.00\",\"version\":0}"));

        assertThat(stale.getStatus()).isEqualTo(409);
        ContractAssertions.assertMatchesGoldenForPath(stale, "admin-error-conflict.json", path(id));
        assertThat(row(id).get("name").asString()).isEqualTo("A");
        assertThat(row(id).get("price").asString()).isEqualTo("110.00");
        assertThat(auditRows()).isEqualTo(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ",\"type\":\"COMBO\"",
            ",\"baseProductId\":9",
            ",\"units\":5",
            ",\"active\":false",
            ",\"id\":7",
            ",\"note\":\"x\""})
    void anyPropertyBeyondNamePriceOfferAndVersionIsA400MalformedRequestAndTheProductIsUnchanged(String extra)
            throws Exception {
        long id = created();
        int audit = auditRows();

        MockHttpServletResponse response = send(adminPutJson(path(id),
                "{\"name\":\"Hacked\",\"price\":\"1.00\",\"version\":0" + extra + "}"));

        assertThat(response.getStatus()).as(extra).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-malformed.json", path(id));
        assertThat(row(id).get("name").asString()).isEqualTo("Frasco");
        assertThat(row(id).get("version").asLong()).isZero();
        assertThat(auditRows()).isEqualTo(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"X\",\"price\":\"10.00\"}",
            "{\"name\":\"X\",\"price\":\"10.00\",\"version\":null}",
            "{\"name\":\"X\",\"price\":\"10.00\",\"version\":-1}",
            "{\"name\":\"X\",\"price\":\"10.00\",\"version\":9007199254740992}",
            "{\"price\":\"10.00\",\"version\":0}",
            "{\"name\":\"X\",\"version\":0}"})
    void aMissingOrOutOfBoundsFieldIsA400ValidationError(String body) throws Exception {
        long id = created();

        MockHttpServletResponse response = send(adminPutJson(path(id), body));

        assertThat(response.getStatus()).as(body).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-validation.json", path(id));
        assertThat(row(id).get("version").asLong()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"price\":0|INVALID_PRICE_PAIR",
            "\"price\":\"-5.00\"|INVALID_PRICE_PAIR",
            "\"price\":\"1.234\"|INVALID_PRICE_PAIR",
            "\"price\":\"100.00\",\"compareAtPrice\":\"100.00\"|INVALID_PRICE_PAIR",
            "\"price\":\"10.00\",\"name\":\"   \"|INVALID_PRODUCT_NAME",
            "\"price\":\"10.00\",\"name\":\"Fra\\u0007sco\"|INVALID_CHARACTERS"})
    void aRuleBreakingEditIs400WithTheReasonAndTheProductIsUnchanged(String fieldsAndReason) throws Exception {
        long id = created();
        String[] parts = fieldsAndReason.split("\\|");
        String body = parts[0].contains("\"name\"") ? "{" + parts[0] + ",\"version\":0}"
                : "{\"name\":\"Frasco\"," + parts[0] + ",\"version\":0}";

        MockHttpServletResponse response = send(adminPutJson(path(id), body));

        assertThat(response.getStatus()).as(body).isEqualTo(400);
        assertThat(json.readTree(response.getContentAsString()).get("reason").asString()).isEqualTo(parts[1]);
        assertThat(row(id).get("version").asLong()).isZero();
        assertThat(row(id).get("price").asString()).isEqualTo("100.00");
    }

    @Test
    void anUnknownIdIs404AndTheIdBoundsAre400() throws Exception {
        String body = "{\"name\":\"X\",\"price\":\"10.00\",\"version\":0}";

        MockHttpServletResponse unknown = send(adminPutJson(path(987654), body));

        assertThat(unknown.getStatus()).isEqualTo(404);
        ContractAssertions.assertMatchesGoldenForPath(unknown, "admin-error-not-found.json", path(987654));
        for (String id : List.of("0", "-1", "9007199254740992", "abc")) {
            assertThat(send(adminPutJson("/api/admin/products/" + id, body)).getStatus()).as(id).isEqualTo(400);
        }
        assertThat(auditRows()).isZero();
    }

    @Test
    void withoutATokenIs403AndTheProductIsUnchanged() throws Exception {
        long id = created();

        MockHttpServletResponse response = send(put(path(id)).with(user("admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hacked\",\"price\":\"1.00\",\"version\":0}"));

        assertThat(response.getStatus()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-forbidden.json", path(id));
        assertThat(row(id).get("name").asString()).isEqualTo("Frasco");
    }

    @Test
    void aPastOrderKeepsItsPriceAndTotalAndANewOrderUsesTheNewPrice() throws Exception {
        long id = created();
        inventory.adjustBy(id, 10);
        long past = placement.placeOrder(order(id, 2)).orderId();

        assertThat(send(adminPutJson(path(id), "{\"name\":\"Frasco\",\"price\":\"150.00\",\"version\":0}")).getStatus())
                .isEqualTo(200);
        long fresh = placement.placeOrder(order(id, 1)).orderId();

        JsonNode pastDetail = json.readTree(send(adminGet("/api/admin/orders/" + past)).getContentAsString());
        JsonNode freshDetail = json.readTree(send(adminGet("/api/admin/orders/" + fresh)).getContentAsString());
        assertThat(pastDetail.get("lines").get(0).get("unitPrice").asString()).isEqualTo("100.00");
        assertThat(pastDetail.get("total").asString()).isEqualTo("200.00");
        assertThat(freshDetail.get("lines").get(0).get("unitPrice").asString()).isEqualTo("150.00");
        assertThat(freshDetail.get("total").asString()).isEqualTo("150.00");
    }

    private static PlaceOrderCommand order(long productId, int quantity) {
        return new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer("Ana Gomez", "+54935155501" + (10 + quantity), "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(productId, quantity)));
    }
}
