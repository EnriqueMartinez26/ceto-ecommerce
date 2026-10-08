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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Error contract of the checkout endpoint (REQ-CHK-01, REQ-CHK-03, REQ-ERR-01/03): one golden file per error code,
 * the core reasons passed through, and no side effect behind a rejected request. Error bodies carry no {@code type}
 * member (verification U-14) and never an order id or number. Every error body is also {@code no-store}, enforced
 * in the shared {@link #problem} helper.
 *
 * <p>Scope: these are the responses the {@code @RestControllerAdvice} produces. Container-level {@code /error}
 * responses (a failure outside the dispatcher servlet, D-20261007-23) get neither {@code no-store} nor the advice
 * shape, and are not covered here. MockMvc echo is silenced ({@code print = NONE}) so the captured output holds
 * only what the server itself logs.
 */
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CheckoutErrorsHttpIT extends AbstractHttpIT {

    private static final String PATH = "/api/checkout/orders";
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
        MockHttpServletRequestBuilder request = post(PATH)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private static String freshKey() {
        return UUID.randomUUID().toString();
    }

    private MockHttpServletResponse problem(MockHttpServletResponse response, int status, String code)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = json.readTree(stripRequestId(response));
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.has("type")).isFalse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("orderNumber", "CETO-", "Maria", "5550000", "Calle Falsa");
        return response;
    }

    /** A rejected request leaves no order, no reservation and no consumed order number: the next one is number 1. */
    private void assertNothingPersistedAndSequenceNotAdvanced() throws Exception {
        assertThat(orderCount()).isZero();
        assertThat(reserved()).isZero();
        MockHttpServletResponse next = send(checkout(freshKey(), BODY));
        assertThat(bodyOf(next).get("orderNumber").asString()).isEqualTo("CETO-000001");
    }

    private JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private long orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    private long reserved() {
        return jdbc.queryForObject("SELECT coalesce(sum(reserved), 0) FROM inventory_stock", Long.class);
    }

    // ---- golden files, one per error code ----

    @Test
    void validationErrorMatchesTheGoldenFile() throws Exception {
        MockHttpServletResponse response = send(checkout(null, BODY));

        problem(response, 400, "VALIDATION_ERROR");
        assertMatchesGolden(response, "error-validation.json");
    }

    @Test
    void malformedRequestMatchesTheGoldenFile() throws Exception {
        MockHttpServletResponse response = send(checkout(freshKey(), "{\"buyer\": "));

        problem(response, 400, "MALFORMED_REQUEST");
        assertMatchesGolden(response, "error-malformed.json");
    }

    @Test
    void unsupportedMediaTypeMatchesTheGoldenFile() throws Exception {
        MockHttpServletResponse response = send(post(PATH).contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.APPLICATION_JSON).header("Idempotency-Key", freshKey()).content(BODY));

        problem(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertMatchesGolden(response, "error-unsupported-media-type.json");
        assertNothingPersistedAndSequenceNotAdvanced();
    }

    @Test
    void notFoundMatchesTheGoldenFile() throws Exception {
        MockHttpServletResponse response = send(post("/api/checkout/unknown").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).content(BODY));

        problem(response, 404, "NOT_FOUND");
        assertMatchesGolden(response, "error-not-found.json");
    }

    @Test
    void anInactiveProductMatchesTheProductUnavailableGoldenFile() throws Exception {
        jdbc.update("UPDATE products SET active = false WHERE id = 2");

        MockHttpServletResponse response = send(checkout(freshKey(), BODY));

        problem(response, 409, "PRODUCT_UNAVAILABLE");
        assertMatchesGolden(response, "error-product-unavailable.json");
        assertThat(orderCount()).isZero();
        assertThat(reserved()).isZero();
    }

    @Test
    void notEnoughStockMatchesTheGoldenFileAndNamesNoProduct() throws Exception {
        MockHttpServletResponse response = send(checkout(freshKey(), BODY.replace("\"quantity\":3", "\"quantity\":11")));

        problem(response, 409, "INSUFFICIENT_STOCK");
        assertMatchesGolden(response, "error-insufficient-stock.json");
        assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).has("productIds")).isFalse();
        assertThat(orderCount()).isZero();
        assertThat(reserved()).isZero();
    }

    @Test
    void aKeyReusedForAnotherPayloadMatchesTheGoldenFileAndNamesNoOrder() throws Exception {
        String key = freshKey();
        send(checkout(key, BODY));

        MockHttpServletResponse response = send(checkout(key, BODY.replace("\"quantity\":3", "\"quantity\":4")));

        problem(response, 409, "IDEMPOTENCY_CONFLICT");
        assertMatchesGolden(response, "error-idempotency-conflict.json");
        assertThat(orderCount()).isEqualTo(1L);
    }

    @Test
    void aFourthPendingOrderOfTheSamePhoneMatchesTheGoldenFileAndHoldsNothing(CapturedOutput output)
            throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(send(checkout(freshKey(), BODY)).getStatus()).isEqualTo(201);
        }
        long reservedBefore = reserved();

        MockHttpServletResponse response = send(checkout(freshKey(), BODY));

        problem(response, 429, "TOO_MANY_PENDING_ORDERS");
        assertMatchesGolden(response, "error-too-many-pending.json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(orderCount()).isEqualTo(3L);
        assertThat(reserved()).isEqualTo(reservedBefore);
        // Logged at DEBUG (a client can trigger it at will): invisible at the test profile's INFO level, and no PII.
        assertThat(output.getAll()).doesNotContain("TOO_MANY_PENDING_ORDERS", "5550000", "Maria");
    }

    @Test
    void replayingAKeyAtTheCapIsStillA200() throws Exception {
        String key = freshKey();
        assertThat(send(checkout(key, BODY)).getStatus()).isEqualTo(201);
        assertThat(send(checkout(freshKey(), BODY)).getStatus()).isEqualTo(201);
        assertThat(send(checkout(freshKey(), BODY)).getStatus()).isEqualTo(201);

        MockHttpServletResponse replay = send(checkout(key, BODY));

        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(bodyOf(replay).get("replayed").asBoolean()).isTrue();
        assertThat(orderCount()).isEqualTo(3L);
    }

    // ---- request contract ----

    @Test
    void aStrayPriceOnALineIsMalformedAndPersistsNothing() throws Exception {
        String withPrice = BODY.replace("\"quantity\":3", "\"quantity\":3,\"price\":1");

        MockHttpServletResponse response = send(checkout(freshKey(), withPrice));

        problem(response, 400, "MALFORMED_REQUEST");
        assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).has("reason")).isFalse();
        assertThat(orderCount()).isZero();
        assertThat(reserved()).isZero();
        // The order number sequence was not consumed: the next valid order is still number 1.
        MockHttpServletResponse next = send(checkout(freshKey(), BODY));
        assertThat(json.readTree(next.getContentAsString(StandardCharsets.UTF_8)).get("orderNumber").asString())
                .isEqualTo("CETO-000001");
    }

    @ParameterizedTest(name = "key [{0}] -> MISSING_IDEMPOTENCY_KEY")
    @CsvSource(value = {"''", "'   '", "not-a-uuid"})
    void aBlankOrBadKeyIsAValidationErrorWithTheMissingKeyReason(String key) throws Exception {
        MockHttpServletResponse response = send(checkout(key, BODY));

        problem(response, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("reason").asString())
                .isEqualTo("MISSING_IDEMPOTENCY_KEY");
        assertThat(orderCount()).isZero();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "\"quantity\":3|\"quantity\":31|QUANTITY_ABOVE_MAX",
            "\"quantity\":3|\"quantity\":0|QUANTITY_BELOW_MIN",
            "\"zone\":\"RESTO_DEL_PAIS\"|\"zone\":\"MARS\"|INVALID_ZONE",
            "+5493515550000|123|INVALID_PHONE",
            "+5493515550000|+34911234567|INVALID_PHONE"})
    void coreAndStructuralReasonsPassThroughToTheClient(String from, String to, String reason) throws Exception {
        MockHttpServletResponse response = send(checkout(freshKey(), BODY.replace(from, to)));

        problem(response, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("reason").asString())
                .isEqualTo(reason);
        assertThat(orderCount()).isZero();
    }

    @Test
    void emptyLinesAreReportedAsAnEmptyCart() throws Exception {
        String empty = "{\"buyer\":{\"name\":\"Maria Perez\",\"phone\":\"+5493515550000\","
                + "\"address\":\"Calle Falsa 123\",\"zone\":\"RESTO_DEL_PAIS\"},\"lines\":[]}";

        MockHttpServletResponse response = send(checkout(freshKey(), empty));

        problem(response, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("reason").asString())
                .isEqualTo("EMPTY_CART");
    }

    @Test
    void duplicateProductLinesAreReported() throws Exception {
        String duplicated = BODY.replace("\"productId\":1", "\"productId\":2");

        MockHttpServletResponse response = send(checkout(freshKey(), duplicated));

        problem(response, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("reason").asString())
                .isEqualTo("DUPLICATE_PRODUCT_LINE");
    }

    @Test
    void aFormEncodedBodyIsRejectedWith415AndNoOrder() throws Exception {
        MockHttpServletResponse response = send(post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON).header("Idempotency-Key", freshKey())
                .content("buyer=x&lines=y".getBytes(StandardCharsets.UTF_8)));

        problem(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertNothingPersistedAndSequenceNotAdvanced();
    }

    @Test
    void aRequestWithoutContentTypeIsRejectedWith415() throws Exception {
        MockHttpServletResponse response = send(post(PATH).accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", freshKey()).content(BODY.getBytes(StandardCharsets.UTF_8)));

        problem(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertNothingPersistedAndSequenceNotAdvanced();
    }

    @Test
    void aTextPlainBodyIsRejectedWith415AndNeverReachesThePlacementService() throws Exception {
        MockHttpServletResponse response = send(post(PATH).contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.APPLICATION_JSON).header("Idempotency-Key", freshKey()).content(BODY));

        problem(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertNothingPersistedAndSequenceNotAdvanced();
    }

    // ---- malformed and structurally invalid bodies ----

    @ParameterizedTest(name = "unknown property: {1}")
    @CsvSource(delimiter = '|', value = {
            "\"quantity\":3|\"quantity\":3,\"price\":1",
            "\"quantity\":3|\"quantity\":3,\"total\":\"1.00\"",
            "\"quantity\":3|\"quantity\":3,\"orderId\":7",
            "\"lines\"|\"price\":1,\"lines\"",
            "\"zone\":\"RESTO_DEL_PAIS\"|\"zone\":\"RESTO_DEL_PAIS\",\"total\":5",
            "\"buyer\"|\"orderId\":7,\"buyer\""})
    void anyUnknownPropertyAtAnyLevelIsMalformedAndPersistsNothing(String from, String to) throws Exception {
        MockHttpServletResponse response = send(checkout(freshKey(), BODY.replace(from, to)));

        problem(response, 400, "MALFORMED_REQUEST");
        assertThat(bodyOf(response).has("reason")).isFalse();
        assertThat(orderCount()).isZero();
        assertThat(reserved()).isZero();
    }

    @Test
    void aNullElementInLinesIsReportedAsAnInvalidLine() throws Exception {
        String body = BODY.replace("{\"productId\":1,\"quantity\":2}", "null");

        MockHttpServletResponse response = send(checkout(freshKey(), body));

        problem(response, 400, "VALIDATION_ERROR");
        assertThat(bodyOf(response).get("reason").asString()).isEqualTo("INVALID_LINE");
        assertThat(orderCount()).isZero();
    }

    @Test
    void aNullBuyerIsAClientErrorNeverAServerError() throws Exception {
        String body = "{\"buyer\":null,\"lines\":[{\"productId\":1,\"quantity\":1}]}";

        MockHttpServletResponse response = send(checkout(freshKey(), body));

        problem(response, 400, "VALIDATION_ERROR");
        assertThat(orderCount()).isZero();
    }

    @Test
    void moreLinesThanTheStructuralCapAreReportedAsTooManyLines() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= 51; i++) {
            lines.append(i == 1 ? "" : ",").append("{\"productId\":").append(i).append(",\"quantity\":1}");
        }
        String body = "{\"buyer\":{\"name\":\"Maria Perez\",\"phone\":\"+5493515550000\","
                + "\"address\":\"Calle Falsa 123\",\"zone\":\"RESTO_DEL_PAIS\"},\"lines\":[" + lines + "]}";

        MockHttpServletResponse response = send(checkout(freshKey(), body));

        problem(response, 400, "VALIDATION_ERROR");
        assertThat(bodyOf(response).get("reason").asString()).isEqualTo("TOO_MANY_LINES");
        assertThat(orderCount()).isZero();
    }

    // ---- nothing the client typed is echoed or logged ----

    @Test
    void sentinelBuyerDataInRejectedRequestsIsNeitherEchoedNorLogged(CapturedOutput output) throws Exception {
        String invalidZone = "{\"buyer\":{\"name\":\"SentinelNameZXQ\",\"phone\":\"+5493519998877\","
                + "\"address\":\"SentinelAddressZXQ 999\",\"zone\":\"MARS\"},"
                + "\"lines\":[{\"productId\":1,\"quantity\":1}]}";
        String strayField = invalidZone.replace("\"lines\"", "\"price\":\"SentinelPriceZXQ\",\"lines\"");
        String unparsable = invalidZone.substring(0, invalidZone.length() - 5);

        for (String body : new String[] {invalidZone, strayField, unparsable}) {
            MockHttpServletResponse response = send(checkout(freshKey(), body));
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain("SentinelNameZXQ", "9998877", "SentinelAddressZXQ", "SentinelPriceZXQ", "MARS");
        }
        // A logged exchange carrying the same sentinel data, so the assertion below cannot pass on an empty log:
        // the 400s above log at DEBUG, an idempotency conflict logs its code at INFO.
        String valid = invalidZone.replace("MARS", "RESTO_DEL_PAIS");
        String key = freshKey();
        assertThat(send(checkout(key, valid)).getStatus()).isEqualTo(201);
        assertThat(send(checkout(key, valid.replace("\"quantity\":1", "\"quantity\":2"))).getStatus()).isEqualTo(409);

        assertThat(output.getAll()).contains("IDEMPOTENCY_CONFLICT").doesNotContain("SentinelNameZXQ", "9998877", "SentinelAddressZXQ",
                "SentinelPriceZXQ", "MARS");
    }

    // ---- Cache-Control on errors (REQ-CHK-02) ----

    @Test
    void errorResponsesOfTheCheckoutEndpointAreNeverCached() throws Exception {
        MockHttpServletResponse badRequest = send(checkout(null, BODY));
        String key = freshKey();
        send(checkout(key, BODY));
        MockHttpServletResponse conflict = send(checkout(key, BODY.replace("\"quantity\":3", "\"quantity\":4")));

        assertThat(badRequest.getStatus()).isEqualTo(400);
        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(badRequest.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(conflict.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    // ---- StockNotConfigured on the buyer path (REQ-ERR-04) ----

    @Test
    void aBaseWithoutAStockRowIsUnavailableToTheBuyerAndLoggedAtErrorForTheOperator(CapturedOutput output)
            throws Exception {
        long unconfigured = catalog.createBase(product("No stock row", "10.00")).id();
        String body = BODY.replace("\"productId\":1", "\"productId\":" + unconfigured);

        MockHttpServletResponse response = send(checkout(freshKey(), body));

        problem(response, 409, "PRODUCT_UNAVAILABLE");
        JsonNode parsed = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        // Only the requested product over the unconfigured base is listed, not the healthy line (id 2).
        assertThat(parsed.get("productIds")).hasSize(1);
        assertThat(parsed.get("productIds").get(0).asLong()).isEqualTo(unconfigured);
        assertThat(output.getAll()).contains("ERROR")
                .contains("StockNotConfiguredException baseProductIds=[" + unconfigured + "]")
                .doesNotContain("5550000", "Maria", "Calle Falsa");
        assertThat(orderCount()).isZero();
        assertThat(reserved()).isZero();
    }
}
