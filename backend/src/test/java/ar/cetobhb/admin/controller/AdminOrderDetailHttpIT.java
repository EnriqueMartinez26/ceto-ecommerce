package ar.cetobhb.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /api/admin/orders/{id}} (AOR-03, AOR-06 bounds, AOR-08): the detail view, its countdown and its errors. */
class AdminOrderDetailHttpIT extends AbstractAdminOrdersHttpIT {

    @Autowired
    private JsonMapper json;

    private long pendingOrder() {
        return place("Ana Gomez", "+5493515550101", "Calle Falsa 123", 2);
    }

    @Test
    void aPendingOrderMatchesTheGoldenWithItsCountdown() throws Exception {
        long id = pendingOrder();
        clock.set(MutableClock.START.plus(Duration.ofDays(1)));

        MockHttpServletResponse response = send(adminGet("/api/admin/orders/" + id));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        ContractAssertions.assertMatchesGolden(response, "admin-order-detail.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void aPaidOrderHasNullReservationFieldsAndItsPaymentTime() throws Exception {
        long id = pendingOrder();
        clock.set(MutableClock.START.plus(Duration.ofHours(1)));
        lifecycle.markPaid(id);

        MockHttpServletResponse response = send(adminGet("/api/admin/orders/" + id));

        assertThat(response.getStatus()).isEqualTo(200);
        ContractAssertions.assertMatchesGolden(response, "admin-order-detail-paid.json");
    }

    @Test
    void anOverdueButUnexpiredReservationShowsZeroSecondsAndStaysPending() throws Exception {
        long id = pendingOrder();
        clock.set(MutableClock.START.plus(Duration.ofHours(49)));

        JsonNode detail = json.readTree(send(adminGet("/api/admin/orders/" + id)).getContentAsString());

        assertThat(detail.get("status").asString()).isEqualTo("PAGO_PENDIENTE");
        assertThat(detail.get("secondsLeft").asLong()).isZero();
        assertThat(detail.get("overdue").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id))
                .isEqualTo("PAGO_PENDIENTE");
    }

    @Test
    void aCancelledOrderCarriesItsReasonAndTimeAndNoReservation() throws Exception {
        long id = pendingOrder();
        lifecycle.cancel(id, ar.cetobhb.orders.domain.CancelReason.CANCELLED_BY_ADMIN);

        JsonNode detail = json.readTree(send(adminGet("/api/admin/orders/" + id)).getContentAsString());

        assertThat(detail.get("status").asString()).isEqualTo("CANCELADO");
        assertThat(detail.get("cancelReason").asString()).isEqualTo("CANCELLED_BY_ADMIN");
        assertThat(detail.get("cancelledAt").asString()).isEqualTo("2026-03-10T12:00:00Z");
        assertThat(detail.get("reservedUntil").isNull()).isTrue();
        assertThat(detail.get("secondsLeft").isNull()).isTrue();
    }

    @Test
    void anUnknownIdIsA404WithTheNeutralGoldenAndNoIdInTheBody() throws Exception {
        pendingOrder();

        MockHttpServletResponse response = send(adminGet("/api/admin/orders/987654"));

        assertThat(response.getStatus()).isEqualTo(404);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-not-found.json",
                "/api/admin/orders/987654");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9007199254740992", "abc", "1.5", "99999999999999999999"})
    void anIdOutOfItsBoundOrNotANumberIsA400(String id) throws Exception {
        MockHttpServletResponse response = send(adminGet("/api/admin/orders/" + id));

        assertThat(response.getStatus()).as("id=%s", id).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-validation.json",
                "/api/admin/orders/" + id);
    }

    @Test
    void theLargestAllowedIdIsAReadableNotFound() throws Exception {
        assertThat(send(adminGet("/api/admin/orders/9007199254740991")).getStatus()).isEqualTo(404);
        assertThat(send(adminGet("/api/admin/orders/1")).getStatus()).isEqualTo(404);
    }

    @Test
    void anonymousIsA401WithTheGolden() throws Exception {
        long id = pendingOrder();

        MockHttpServletResponse response = send(get("/api/admin/orders/" + id));

        assertThat(response.getStatus()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-unauthenticated.json",
                "/api/admin/orders/" + id);
        assertThat(response.getContentAsString()).doesNotContain("Ana Gomez").doesNotContain("Calle Falsa");
    }
}
