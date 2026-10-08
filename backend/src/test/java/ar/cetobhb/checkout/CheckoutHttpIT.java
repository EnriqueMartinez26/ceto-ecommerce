package ar.cetobhb.checkout;

import static ar.cetobhb.support.CatalogFixtures.product;
import static ar.cetobhb.support.ContractAssertions.assertMatchesGolden;
import static ar.cetobhb.support.ContractAssertions.stripRequestId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractHttpIT;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checkout success contract (REQ-CHK-01..03): 201 on creation, 200 on replay, no internal id, never cached. */
class CheckoutHttpIT extends AbstractHttpIT {

    private static final String BODY = """
            {"buyer":{"name":"Maria Perez","phone":"+5493515550000","address":"Calle Falsa 123",
                      "zone":"RESTO_DEL_PAIS"},
             "lines":[{"productId":2,"quantity":3},{"productId":1,"quantity":2}]}
            """;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @BeforeEach
    void seedCatalog() {
        long shampoo = catalog.createBase(product("Solid shampoo", "45000.00")).id();
        inventory.createStock(shampoo, 10);
        long cream = catalog.createBase(product("Cream", "100.50")).id();
        inventory.createStock(cream, 10);
    }

    private static MockHttpServletRequestBuilder checkout(String key, String body) {
        MockHttpServletRequestBuilder request = post("/api/checkout/orders")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    @Test
    void aNewOrderIsCreatedWith201AndMatchesTheGoldenFile() throws Exception {
        MockHttpServletResponse response = send(checkout(UUID.randomUUID().toString(), BODY));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentType()).startsWith("application/json");
        assertMatchesGolden(response, "checkout-201.json");
    }

    @Test
    void theResponseIsNeverCachedAndHasNoLocationHeader() throws Exception {
        MockHttpServletResponse response = send(checkout(UUID.randomUUID().toString(), BODY));

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Location")).isNull();
    }

    @Test
    void noInternalIdOrBuyerDataIsEverReturned() throws Exception {
        MockHttpServletResponse response = send(checkout(UUID.randomUUID().toString(), BODY));
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("orderNumber", "status", "zone", "total",
                "reservedUntil", "replayed", "lines");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("orderId", "Maria", "5550000", "Calle Falsa", "whatsapp", "wa.me");
    }

    @Test
    void theSameKeyAndBodyReplaysWith200AndTheSameOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        send(checkout(key, BODY));

        MockHttpServletResponse replay = send(checkout(key, BODY));

        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(replay.getHeader("Location")).isNull();
        assertMatchesGolden(replay, "checkout-200-replay.json");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = 1", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void aReplayAfterAPriceChangeStillReturnsThePricesChargedAtPlacement() throws Exception {
        String key = UUID.randomUUID().toString();
        send(checkout(key, BODY));
        jdbc.update("UPDATE products SET price = 999.00, name = 'Renamed' WHERE id IN (1, 2)");

        MockHttpServletResponse replay = send(checkout(key, BODY));

        assertThat(replay.getStatus()).isEqualTo(200);
        assertMatchesGolden(replay, "checkout-200-replay.json");
    }

    @Test
    void aReplayOfACancelledOrderReportsCancelledWith200() throws Exception {
        String key = UUID.randomUUID().toString();
        send(checkout(key, BODY));
        jdbc.update("UPDATE orders SET status = 'CANCELADO', cancel_reason = 'CANCELLED_BY_ADMIN',"
                + " cancelled_at = now()");
        jdbc.update("UPDATE inventory_stock SET reserved = 0");

        MockHttpServletResponse replay = send(checkout(key, BODY));

        assertThat(replay.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(stripRequestId(replay));
        assertThat(body.get("status").asString()).isEqualTo("CANCELADO");
        assertThat(body.get("replayed").asBoolean()).isTrue();
    }

    /** U-13: a constrained header makes Framework 7 report body violations as HandlerMethodValidationException. */
    @Test
    void bodyViolationsAreReportedAsMethodValidationErrorsWithTheReasonAndNoType() throws Exception {
        String badQuantity = BODY.replace("\"quantity\":3", "\"quantity\":0");

        MvcResult result = sendForResult(checkout(UUID.randomUUID().toString(), badQuantity));

        assertThat(result.getResolvedException()).isInstanceOf(HandlerMethodValidationException.class);
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = json.readTree(stripRequestId(response));
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("reason").asString()).isEqualTo("QUANTITY_BELOW_MIN");
        assertThat(body.has("type")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
    }

    @Test
    void anOverlongKeyIsAValidationErrorWithTheMissingKeyReason() throws Exception {
        MvcResult result = sendForResult(checkout("k".repeat(65), BODY));

        assertThat(result.getResolvedException()).isInstanceOf(HandlerMethodValidationException.class);
        JsonNode body = json.readTree(stripRequestId(result.getResponse()));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body.get("reason").asString()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void anAbsentKeyIsRejectedByTheBusinessRuleNotByTheFramework() throws Exception {
        MvcResult result = sendForResult(checkout(null, BODY));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = json.readTree(stripRequestId(result.getResponse()));
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("reason").asString()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void anUnknownZoneReachesTheNormalizerAndIsReportedAsInvalidZone() throws Exception {
        MvcResult result = sendForResult(checkout(UUID.randomUUID().toString(),
                BODY.replace("RESTO_DEL_PAIS", "MARS")));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = json.readTree(stripRequestId(result.getResponse()));
        assertThat(body.get("reason").asString()).isEqualTo("INVALID_ZONE");
    }
}
