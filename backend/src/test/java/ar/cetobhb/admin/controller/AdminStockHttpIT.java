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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code GET /api/admin/stock} and {@code POST /api/admin/stock/{productId}/adjust} (AINV-01 to AINV-04, AINV-07,
 * ERR-08, HRD-11): the view with combo derivation and an unconfigured entry, the relative adjustment with its audit row,
 * every bound of the body, the rejected note (D-20261008-23), and the conflicts that never reveal figures.
 */
class AdminStockHttpIT extends AbstractAdminHttpIT {

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long base(String name, int onHand) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private void reserve(long productId, int quantity) {
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer("Ana Gomez", "+5493515550101", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(productId, quantity))));
    }

    private int[] counts(long productId) {
        return jdbc.queryForObject("SELECT on_hand, reserved FROM inventory_stock WHERE product_id = ?",
                (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}, productId);
    }

    private static String adjustPath(long productId) {
        return "/api/admin/stock/" + productId + "/adjust";
    }

    // ---- view -----------------------------------------------------------------------------------------------

    @Test
    void theStockViewMatchesTheGoldenWithComboDerivationAndAnUnconfiguredEntry() throws Exception {
        long frasco = base("Frasco", 10);
        reserve(frasco, 3);
        catalog.createCombo(product("Combo x2", "180.00"), frasco, 2);
        long retired = base("Retirado", 5);
        catalog.setActive(retired, false);
        catalog.createBase(product("Sin stock", "90.00"));

        MockHttpServletResponse response = send(adminGet("/api/admin/stock"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        ContractAssertions.assertMatchesGolden(response, "admin-stock.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(auditRows()).isZero();
    }

    @Test
    void anEmptyCatalogIsAnEmptyUntruncatedList() throws Exception {
        MockHttpServletResponse response = send(adminGet("/api/admin/stock"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"products\":[],\"truncated\":false}");
    }

    @Test
    void theStockViewWithoutASessionIs401AndThePostWithoutATokenIs403() throws Exception {
        long id = base("Frasco", 10);

        MockHttpServletResponse view = send(get("/api/admin/stock"));
        MockHttpServletResponse anonymousAdjust = send(post(adjustPath(id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":1,\"reason\":\"RESTOCK\"}"));
        MockHttpServletResponse noToken = send(post(adjustPath(id)).with(user("admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":1,\"reason\":\"RESTOCK\"}"));

        assertThat(view.getStatus()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(view, "admin-error-unauthenticated.json", "/api/admin/stock");
        assertThat(anonymousAdjust.getStatus()).isEqualTo(403);
        assertThat(noToken.getStatus()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(noToken, "admin-error-forbidden.json", adjustPath(id));
        assertThat(counts(id)).containsExactly(10, 0);
        assertThat(auditRows()).isZero();
    }

    // ---- adjust ---------------------------------------------------------------------------------------------

    @Test
    void aRestockReturnsTheNewCountersMatchesTheGoldenAndWritesOneAuditRowWithBeforeAndAfter() throws Exception {
        long id = base("Frasco", 5);
        reserve(id, 2);

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), "{\"delta\":20,\"reason\":\"RESTOCK\"}"));

        assertThat(response.getStatus()).isEqualTo(200);
        ContractAssertions.assertMatchesGolden(response, "admin-stock-adjusted.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(counts(id)).containsExactly(25, 2);
        assertThat(auditActions()).containsExactly("STOCK_ADJUSTED");
        assertThat(jdbc.queryForMap("SELECT reason, before_value, after_value FROM admin_audit_log"))
                .containsEntry("reason", "RESTOCK")
                .containsEntry("before_value", new java.math.BigDecimal("5.00"))
                .containsEntry("after_value", new java.math.BigDecimal("25.00"));
    }

    @Test
    void aNegativeAdjustmentDownToExactlyReservedIs200() throws Exception {
        long id = base("Frasco", 10);
        reserve(id, 8);

        MockHttpServletResponse response = send(
                adminPostJson(adjustPath(id), "{\"delta\":-2,\"reason\":\"DAMAGED_OR_LOST\"}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(counts(id)).containsExactly(8, 8);
    }

    @Test
    void everyReasonOfTheClosedSetIsAccepted() throws Exception {
        long id = base("Frasco", 10);

        for (String reason : List.of("COUNT_CORRECTION", "RESTOCK", "DAMAGED_OR_LOST", "RETURN", "OTHER")) {
            assertThat(send(adminPostJson(adjustPath(id), "{\"delta\":1,\"reason\":\"" + reason + "\"}")).getStatus())
                    .as(reason).isEqualTo(200);
        }
        assertThat(counts(id)).containsExactly(15, 0);
        assertThat(auditRows()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"delta\":10001,\"reason\":\"RESTOCK\"}",
            "{\"delta\":-10001,\"reason\":\"RESTOCK\"}",
            "{\"delta\":2147483647,\"reason\":\"RESTOCK\"}",
            "{\"reason\":\"RESTOCK\"}",
            "{\"delta\":null,\"reason\":\"RESTOCK\"}",
            "{\"delta\":5}",
            "{\"delta\":5,\"reason\":null}"})
    void aDeltaOutsideTheBoundsOrAMissingFieldIsA400ValidationErrorWithNoChangeAndNoAuditRow(String body)
            throws Exception {
        long id = base("Frasco", 10);

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), body));

        assertThat(response.getStatus()).as(body).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-validation.json", adjustPath(id));
        assertThat(counts(id)).containsExactly(10, 0);
        assertThat(auditRows()).isZero();
    }

    @Test
    void aZeroDeltaIsA400ValidationErrorWithTheStockDeltaReasonAndNoChange() throws Exception {
        long id = base("Frasco", 10);

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), "{\"delta\":0,\"reason\":\"RESTOCK\"}"));

        assertThat(response.getStatus()).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-invalid-stock-delta.json", adjustPath(id));
        assertThat(counts(id)).containsExactly(10, 0);
        assertThat(auditRows()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"delta\":1.5,\"reason\":\"RESTOCK\"}",
            "{\"delta\":\"x\",\"reason\":\"RESTOCK\"}",
            "{\"delta\":\"5\",\"reason\":\"RESTOCK\"}",
            "{\"delta\":1,\"reason\":\"FOO\"}",
            "{\"delta\":1,\"reason\":\"restock\"}",
            "{\"delta\":1,\"reason\":7}",
            "{\"delta\":1,\"reason\":\"RESTOCK\",\"note\":\"SentinelNoteText\"}",
            "{\"delta\":1,\"reason\":\"RESTOCK\",\"productId\":9}",
            "{\"delta\":1,\"delta\":2,\"reason\":\"RESTOCK\"}",
            "[]",
            "{\"delta\":1,\"reason\":\"RESTOCK\"",
            ""})
    void aTypeErrorAnUnknownReasonANoteAnUnknownPropertyOrBrokenJsonIsA400MalformedRequestWithNoChange(String body)
            throws Exception {
        long id = base("Frasco", 10);

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), body));

        assertThat(response.getStatus()).as(body).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-malformed.json", adjustPath(id));
        assertThat(response.getContentAsString()).doesNotContain("SentinelNoteText").doesNotContain("FOO");
        assertThat(counts(id)).containsExactly(10, 0);
        assertThat(auditRows()).isZero();
    }

    @Test
    void aBodyWithAnotherContentTypeIsA415() throws Exception {
        long id = base("Frasco", 10);

        MockHttpServletResponse response = send(adminPost(adjustPath(id)).contentType(MediaType.TEXT_PLAIN)
                .content("{\"delta\":1,\"reason\":\"RESTOCK\"}"));

        assertThat(response.getStatus()).isEqualTo(415);
        assertThat(counts(id)).containsExactly(10, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9007199254740992", "abc", "1.5"})
    void aProductIdOutsideTheBoundsOrNotANumberIsA400WithNoChange(String id) throws Exception {
        base("Frasco", 10);

        MockHttpServletResponse response = send(
                adminPostJson("/api/admin/stock/" + id + "/adjust", "{\"delta\":1,\"reason\":\"RESTOCK\"}"));

        assertThat(response.getStatus()).as(id).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-validation.json",
                "/api/admin/stock/" + id + "/adjust");
        assertThat(auditRows()).isZero();
    }

    @Test
    void goingBelowReservedIsA409InsufficientStockWithoutFiguresLeavingTheRowAndTheLogUntouched() throws Exception {
        long id = base("Frasco", 10);
        reserve(id, 8);

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), "{\"delta\":-3,\"reason\":\"OTHER\"}"));

        assertThat(response.getStatus()).isEqualTo(409);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-insufficient-stock.json", adjustPath(id));
        assertThat(counts(id)).containsExactly(10, 8);
        assertThat(auditRows()).isZero();
    }

    @Test
    void goingBelowZeroIsA409AndLeavesTheRowUnchanged() throws Exception {
        long id = base("Frasco", 3);

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), "{\"delta\":-4,\"reason\":\"OTHER\"}"));

        assertThat(response.getStatus()).isEqualTo(409);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-insufficient-stock.json", adjustPath(id));
        assertThat(counts(id)).containsExactly(3, 0);
        assertThat(auditRows()).isZero();
    }

    @Test
    void adjustingTheSumPastTheIntegerRangeIsA400WithTheRangeReason() throws Exception {
        long id = base("Frasco", Integer.MAX_VALUE - 5);

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), "{\"delta\":10,\"reason\":\"RESTOCK\"}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"reason\":\"STOCK_OUT_OF_RANGE\"");
        assertThat(counts(id)).containsExactly(Integer.MAX_VALUE - 5, 0);
        assertThat(auditRows()).isZero();
    }

    @Test
    void aComboIdIsA409ConflictWithNoChangeAndNoStockRowCreated() throws Exception {
        long frasco = base("Frasco", 10);
        long combo = catalog.createCombo(product("Combo x2", "180.00"), frasco, 2).id();

        MockHttpServletResponse response = send(adminPostJson(adjustPath(combo), "{\"delta\":1,\"reason\":\"RESTOCK\"}"));

        assertThat(response.getStatus()).isEqualTo(409);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-conflict.json", adjustPath(combo));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_stock WHERE product_id = ?", Integer.class,
                combo)).isZero();
        assertThat(counts(frasco)).containsExactly(10, 0);
        assertThat(auditRows()).isZero();
    }

    @Test
    void aBaseProductWithoutAStockRowIsA409ConflictAndNeverGetsOne() throws Exception {
        long id = catalog.createBase(product("Sin stock", "90.00")).id();

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), "{\"delta\":5,\"reason\":\"RESTOCK\"}"));

        assertThat(response.getStatus()).isEqualTo(409);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-conflict.json", adjustPath(id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_stock WHERE product_id = ?", Integer.class,
                id)).isZero();
        assertThat(auditRows()).isZero();
    }

    @Test
    void anUnknownProductIsA404NotFoundWithNoAuditRow() throws Exception {
        MockHttpServletResponse response = send(adminPostJson(adjustPath(987654),
                "{\"delta\":5,\"reason\":\"RESTOCK\"}"));

        assertThat(response.getStatus()).isEqualTo(404);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-not-found.json", adjustPath(987654));
        assertThat(auditRows()).isZero();
    }

    @Test
    void anInactiveBaseProductCanStillBeAdjusted() throws Exception {
        long id = base("Retirado", 4);
        catalog.setActive(id, false);

        MockHttpServletResponse response = send(adminPostJson(adjustPath(id), "{\"delta\":6,\"reason\":\"RETURN\"}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(counts(id)).containsExactly(10, 0);
    }

    @Test
    void theAdjustRouteAnswers405ToAGetAndTheOldAbsoluteSetRouteDoesNotExist() throws Exception {
        long id = base("Frasco", 10);

        assertThat(send(adminGet(adjustPath(id))).getStatus()).isEqualTo(405);
        assertThat(send(adminPostJson("/api/admin/stock/" + id, "{\"onHand\":99}")).getStatus()).isEqualTo(404);
        assertThat(send(adminPostJson("/api/admin/stock/" + id + "/set", "{\"onHand\":99}")).getStatus())
                .isEqualTo(404);
        assertThat(counts(id)).containsExactly(10, 0);
    }
}
