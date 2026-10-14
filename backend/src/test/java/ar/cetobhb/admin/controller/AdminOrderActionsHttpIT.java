package ar.cetobhb.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/admin/orders/{id}/pay|dispatch|deliver|cancel} (AOR-04, AOR-05, AOR-06, AUTH-05, ERR-06): the
 * action runs the lifecycle call, writes one audit row and returns the updated detail; an illegal edge is a 409 with
 * nothing changed; a missing CSRF token is a 403 with nothing changed.
 */
class AdminOrderActionsHttpIT extends AbstractAdminOrdersHttpIT {

    private static final List<String> ACTIONS = List.of("pay", "dispatch", "deliver", "cancel");

    @Autowired
    private JsonMapper json;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long order() {
        return place("Ana Gomez", "+5493515550101", "Calle Falsa 123", 2);
    }

    private static String path(long id, String action) {
        return "/api/admin/orders/" + id + "/" + action;
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private List<String> auditActions() {
        return jdbc.queryForList("SELECT action FROM admin_audit_log ORDER BY id", String.class);
    }

    private JsonNode detail(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    @Test
    void payReturnsTheUpdatedDetailMatchesTheGoldenDecrementsStockOnceAndWritesOneAuditRow() throws Exception {
        long id = order();
        clock.set(MutableClock.START.plus(Duration.ofHours(1)));

        MockHttpServletResponse response = send(adminPost(path(id, "pay")));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        ContractAssertions.assertMatchesGolden(response, "admin-order-detail-paid.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(onHand()).isEqualTo(998);
        assertThat(reserved()).isZero();
        assertThat(auditActions()).containsExactly("ORDER_PAID");
    }

    @Test
    void dispatchAndDeliverMoveTheOrderAlongWithTheirTimesAndOneAuditRowEach() throws Exception {
        long id = order();
        send(adminPost(path(id, "pay")));
        clock.set(MutableClock.START.plus(Duration.ofDays(1)));

        JsonNode dispatched = detail(send(adminPost(path(id, "dispatch"))));
        clock.set(MutableClock.START.plus(Duration.ofDays(2)));
        JsonNode delivered = detail(send(adminPost(path(id, "deliver"))));

        assertThat(dispatched.get("status").asString()).isEqualTo("DESPACHADO");
        assertThat(dispatched.get("dispatchedAt").asString()).isEqualTo("2026-03-11T12:00:00Z");
        assertThat(dispatched.get("deliveredAt").isNull()).isTrue();
        assertThat(delivered.get("status").asString()).isEqualTo("ENTREGADO");
        assertThat(delivered.get("deliveredAt").asString()).isEqualTo("2026-03-12T12:00:00Z");
        assertThat(delivered.get("dispatchedAt").asString()).isEqualTo("2026-03-11T12:00:00Z");
        assertThat(auditActions()).containsExactly("ORDER_PAID", "ORDER_DISPATCHED", "ORDER_DELIVERED");
        assertThat(onHand()).isEqualTo(998);
    }

    @Test
    void cancellingAPendingOrderReleasesTheReservationAndMatchesTheGolden() throws Exception {
        long id = order();
        assertThat(reserved()).isEqualTo(2);
        clock.set(MutableClock.START.plus(Duration.ofMinutes(30)));

        MockHttpServletResponse response = send(adminPost(path(id, "cancel")));

        assertThat(response.getStatus()).isEqualTo(200);
        ContractAssertions.assertMatchesGolden(response, "admin-order-cancelled.json");
        assertThat(reserved()).isZero();
        assertThat(onHand()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, id))
                .isEqualTo(CancelReason.CANCELLED_BY_ADMIN.name());
        assertThat(auditActions()).containsExactly("ORDER_CANCELLED");
    }

    @Test
    void cancellingAPaidOrderReturnsTheUnitsToOnHand() throws Exception {
        long id = order();
        send(adminPost(path(id, "pay")));
        assertThat(onHand()).isEqualTo(998);

        JsonNode cancelled = detail(send(adminPost(path(id, "cancel"))));

        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELADO");
        assertThat(cancelled.get("cancelReason").asString()).isEqualTo("CANCELLED_BY_ADMIN");
        assertThat(onHand()).isEqualTo(1000);
        assertThat(reserved()).isZero();
        assertThat(auditActions()).containsExactly("ORDER_PAID", "ORDER_CANCELLED");
    }

    @Test
    void anOverdueButUnexpiredOrderIsStillPayableOverHttp() throws Exception {
        long id = order();
        clock.set(MutableClock.START.plus(Duration.ofHours(49)));

        assertThat(detail(send(adminPost(path(id, "pay")))).get("status").asString()).isEqualTo("PAGADO");
    }

    @ParameterizedTest
    @CsvSource({"dispatch", "deliver"})
    void anIllegalEdgeFromAPendingOrderIsA409WithTheGoldenAndChangesNothing(String action) throws Exception {
        long id = order();

        MockHttpServletResponse response = send(adminPost(path(id, action)));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-illegal-transition.json",
                path(id, action));
        assertThat(response.getContentAsString()).doesNotContain("PAGO_PENDIENTE").doesNotContain("Ana")
                .doesNotContain("Calle");
        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertThat(reserved()).isEqualTo(2);
        assertThat(onHand()).isEqualTo(1000);
        assertThat(auditActions()).isEmpty();
    }

    @Test
    void aRepeatedTapOfTheSameActionIsA409AndDoesNotApplyTheStockEffectTwice() throws Exception {
        long id = order();
        assertThat(send(adminPost(path(id, "pay"))).getStatus()).isEqualTo(200);

        MockHttpServletResponse second = send(adminPost(path(id, "pay")));

        assertThat(second.getStatus()).isEqualTo(409);
        ContractAssertions.assertMatchesGoldenForPath(second, "admin-error-illegal-transition.json",
                path(id, "pay"));
        assertThat(onHand()).isEqualTo(998);
        assertThat(auditActions()).containsExactly("ORDER_PAID");
    }

    @Test
    void everyActionOnADeliveredAndOnACancelledOrderIsA409AndWritesNothingMore() throws Exception {
        long delivered = order();
        send(adminPost(path(delivered, "pay")));
        send(adminPost(path(delivered, "dispatch")));
        send(adminPost(path(delivered, "deliver")));
        long cancelled = place("Beto Ruiz", "+5493515550102", "Otra calle 5", 1);
        send(adminPost(path(cancelled, "cancel")));
        int rows = auditActions().size();

        for (long id : new long[] {delivered, cancelled}) {
            for (String action : ACTIONS) {
                MockHttpServletResponse response = send(adminPost(path(id, action)));
                assertThat(response.getStatus()).as("%s on %d", action, id).isEqualTo(409);
                ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-illegal-transition.json",
                        path(id, action));
            }
        }

        assertThat(status(delivered)).isEqualTo("ENTREGADO");
        assertThat(status(cancelled)).isEqualTo("CANCELADO");
        assertThat(auditActions()).hasSize(rows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "dispatch", "deliver", "cancel"})
    void anUnknownIdIsA404WithTheNeutralGoldenAndNoAuditRow(String action) throws Exception {
        order();

        MockHttpServletResponse response = send(adminPost(path(987654, action)));

        assertThat(response.getStatus()).isEqualTo(404);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-not-found.json", path(987654, action));
        assertThat(auditActions()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "pay|0", "pay|-1", "pay|9007199254740992", "pay|abc",
            "dispatch|0", "dispatch|9007199254740992", "dispatch|abc",
            "deliver|0", "deliver|9007199254740992", "deliver|abc",
            "cancel|0", "cancel|9007199254740992", "cancel|abc"})
    void anIdOutOfItsBoundOrNotANumberIsA400(String action, String id) throws Exception {
        MockHttpServletResponse response = send(adminPost("/api/admin/orders/" + id + "/" + action));

        assertThat(response.getStatus()).as("%s %s", action, id).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-validation.json",
                "/api/admin/orders/" + id + "/" + action);
        assertThat(auditActions()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "dispatch", "deliver", "cancel"})
    void withoutACsrfTokenEveryActionIsA403WithTheGoldenAndChangesNothing(String action) throws Exception {
        long id = order();
        send(adminPost(path(id, "pay")));
        int rows = auditActions().size();
        String before = status(id);

        MockHttpServletResponse response = send(post(path(id, action)).with(user("admin")));

        assertThat(response.getStatus()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-forbidden.json", path(id, action));
        assertThat(status(id)).isEqualTo(before);
        assertThat(auditActions()).hasSize(rows);
        assertThat(onHand()).isEqualTo(998);
    }

    @Test
    void aTokenThatDoesNotMatchTheCookieIsA403() throws Exception {
        long id = order();

        MockHttpServletResponse response = send(post(path(id, "pay")).with(user("admin"))
                .cookie(new Cookie("__Host-XSRF-TOKEN", XSRF_TOKEN)).header("X-XSRF-TOKEN", "another-token"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertThat(auditActions()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "dispatch", "deliver", "cancel"})
    void anonymousIsA401EvenWithAValidTokenAndChangesNothing(String action) throws Exception {
        long id = order();

        MockHttpServletResponse response = send(post(path(id, action))
                .cookie(new Cookie("__Host-XSRF-TOKEN", XSRF_TOKEN)).header("X-XSRF-TOKEN", XSRF_TOKEN));

        assertThat(response.getStatus()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-unauthenticated.json", path(id, action));
        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertThat(auditActions()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "dispatch", "deliver", "cancel"})
    void anActionPathIsPostOnlyAndAGetNeverChangesAnything(String action) throws Exception {
        long id = order();

        MockHttpServletResponse response = send(get(path(id, action)).with(user("admin")));

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getHeader("Allow")).contains("POST");
        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertThat(auditActions()).isEmpty();
    }

    @Test
    void theActionsTakeNoRequestBodyAndIgnoreOne() throws Exception {
        long id = order();

        MockHttpServletResponse response = send(adminPost(path(id, "pay")).contentType("application/json")
                .content("{\"status\":\"CANCELADO\",\"total\":\"1.00\"}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("PAGADO");
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", java.math.BigDecimal.class, id))
                .isEqualByComparingTo("200.00");
        assertThat(auditActions()).hasSize(1);
    }
}
