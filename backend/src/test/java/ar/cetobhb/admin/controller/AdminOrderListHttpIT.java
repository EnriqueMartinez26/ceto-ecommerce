package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.BuyerFixtures.freshPhone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/admin/orders} (AOR-01, AOR-02, AOR-08, HRD-08): filter, keyset pagination, exact search, bounds, the
 * absence of the address in a row and the no-store header.
 */
class AdminOrderListHttpIT extends AbstractAdminOrdersHttpIT {

    private static final String PATH = "/api/admin/orders";

    @Autowired
    private JsonMapper json;

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("orders").forEach(row -> ids.add(row.get("id").asLong()));
        return ids;
    }

    @Test
    void aPageMatchesTheGoldenAndCarriesNoAddressAndTheNoStoreHeader() throws Exception {
        place("Ana Gomez", "+5493515550101", "Calle Falsa 123", 2);
        lifecycle.markPaid(1);
        clock.set(MutableClock.START.plus(Duration.ofMinutes(5)));
        place("Beto Ruiz", "+5493515550102", "Avenida Siempre Viva 742", 1);

        MockHttpServletResponse response = send(adminGet(PATH));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        ContractAssertions.assertMatchesGolden(response, "admin-orders-list.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentAsString()).doesNotContain("Calle Falsa").doesNotContain("Siempre Viva")
                .doesNotContain("shippingAddress");
    }

    @Test
    void theDefaultPageHasTwentyRowsAndPointsToTheNextOne() throws Exception {
        for (int i = 0; i < 25; i++) {
            place("Buyer " + i, freshPhone(), "Calle " + i, 1);
        }

        JsonNode page = body(send(adminGet(PATH)));

        assertThat(page.get("orders")).hasSize(20);
        assertThat(page.get("nextAfter").asLong()).isEqualTo(6);
    }

    @Test
    void followingNextAfterThroughOneHundredTwentyOrdersGivesFiftyFiftyTwentyWithoutGapsOrDuplicates()
            throws Exception {
        for (int i = 0; i < 120; i++) {
            place("Buyer " + i, freshPhone(), "Calle " + i, 1);
        }
        List<Integer> sizes = new ArrayList<>();
        List<Long> all = new ArrayList<>();
        String after = null;
        for (int guard = 0; guard < 5; guard++) {
            var request = adminGet(PATH).param("size", "50");
            JsonNode page = body(send(after == null ? request : request.param("after", after)));
            sizes.add(page.get("orders").size());
            all.addAll(ids(page));
            if (page.get("nextAfter").isNull()) {
                break;
            }
            after = page.get("nextAfter").asString();
        }

        assertThat(sizes).containsExactly(50, 50, 20);
        assertThat(all).hasSize(120).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(new HashSet<>(all)).hasSize(120).contains(1L, 120L);
    }

    @Test
    void theStatusFilterReturnsOnlyThatStateAndAllMeansEveryState() throws Exception {
        long pending = place("Ana", freshPhone(), "Calle 1", 1);
        long paid = place("Beto", freshPhone(), "Calle 2", 1);
        lifecycle.markPaid(paid);
        long cancelled = place("Cris", freshPhone(), "Calle 3", 1);
        lifecycle.cancel(cancelled, ar.cetobhb.orders.domain.CancelReason.CANCELLED_BY_ADMIN);

        assertThat(ids(body(send(adminGet(PATH).param("status", "PAGADO"))))).containsExactly(paid);
        assertThat(ids(body(send(adminGet(PATH).param("status", "PAGO_PENDIENTE"))))).containsExactly(pending);
        assertThat(ids(body(send(adminGet(PATH).param("status", "CANCELADO"))))).containsExactly(cancelled);
        assertThat(ids(body(send(adminGet(PATH).param("status", "DESPACHADO"))))).isEmpty();
        assertThat(ids(body(send(adminGet(PATH).param("status", "all"))))).containsExactly(cancelled, paid, pending);
        assertThat(ids(body(send(adminGet(PATH))))).containsExactly(cancelled, paid, pending);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CETO-000003", "ceto-000003", "3"})
    void anOrderNumberFindsExactlyThatOrder(String q) throws Exception {
        for (int i = 0; i < 5; i++) {
            place("Buyer " + i, freshPhone(), "Calle " + i, 1);
        }

        assertThat(ids(body(send(adminGet(PATH).param("q", q))))).containsExactly(3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"351 555-0199", "+54 9 351 555 0199", "+5493515550199", "(351) 555-0199"})
    void aPhoneInAnyInputFormFindsTheOrdersOfThatBuyer(String q) throws Exception {
        long wanted = place("Ana", "+5493515550199", "Calle 1", 1);
        place("Beto", freshPhone(), "Calle 2", 1);

        assertThat(ids(body(send(adminGet(PATH).param("q", q))))).containsExactly(wanted);
    }

    @Test
    void thereIsNoPartialMatchAndAnUnreadableSearchIsAnEmptyPageNotAnError() throws Exception {
        for (int i = 0; i < 3; i++) {
            place("Buyer " + i, freshPhone(), "Calle " + i, 1);
        }

        for (String q : List.of("CETO-0001", "CETO-00000", "Buyer", "zzz", "+999")) {
            MockHttpServletResponse response = send(adminGet(PATH).param("q", q));
            JsonNode page = body(response);
            assertThat(page.get("orders")).as("q=%s", q).isEmpty();
            assertThat(page.get("nextAfter").isNull()).isTrue();
        }
    }

    @Test
    void theSearchComposesWithTheStatusFilter() throws Exception {
        long id = place("Ana", "+5493515550199", "Calle 1", 1);
        lifecycle.markPaid(id);

        assertThat(ids(body(send(adminGet(PATH).param("q", "351 555-0199").param("status", "PAGADO")))))
                .containsExactly(id);
        assertThat(ids(body(send(adminGet(PATH).param("q", "351 555-0199").param("status", "PAGO_PENDIENTE")))))
                .isEmpty();
    }

    @Test
    void anOverdueReservationIsFlaggedInItsRowWhileTheOrderStaysPending() throws Exception {
        place("Ana", freshPhone(), "Calle 1", 1);
        clock.set(MutableClock.START.plus(Duration.ofHours(49)));

        JsonNode row = body(send(adminGet(PATH))).get("orders").get(0);

        assertThat(row.get("status").asString()).isEqualTo("PAGO_PENDIENTE");
        assertThat(row.get("overdue").asBoolean()).isTrue();
        assertThat(row.get("reservedUntil").asString()).isEqualTo("2026-03-12T12:00:00Z");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "size|0", "size|51", "size|-1", "size|abc", "size|2147483648", "after|0", "after|-5",
            "after|9007199254740992", "after|abc", "status|X", "status|pagado", "status|PAGADO,CANCELADO",
            "status|ALL"})
    void aParameterOutOfItsBoundIsA400ValidationError(String name, String value) throws Exception {
        MockHttpServletResponse response = send(adminGet(PATH).param(name, value));

        assertThat(response.getStatus()).as("%s=%s", name, value).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-validation.json", PATH);
    }

    @Test
    void aSearchTextOfSixtyFiveCharactersIsA400AndSixtyFourIsNot() throws Exception {
        MockHttpServletResponse tooLong = send(adminGet(PATH).param("q", "1".repeat(65)));
        MockHttpServletResponse limit = send(adminGet(PATH).param("q", "1".repeat(64)));

        assertThat(tooLong.getStatus()).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(tooLong, "admin-error-validation.json", PATH);
        assertThat(limit.getStatus()).isEqualTo(200);
    }

    @Test
    void theBoundsAreInclusiveAtBothEnds() throws Exception {
        assertThat(send(adminGet(PATH).param("size", "1")).getStatus()).isEqualTo(200);
        assertThat(send(adminGet(PATH).param("size", "50")).getStatus()).isEqualTo(200);
        assertThat(send(adminGet(PATH).param("after", "1")).getStatus()).isEqualTo(200);
        assertThat(send(adminGet(PATH).param("after", "9007199254740991")).getStatus()).isEqualTo(200);
    }

    @Test
    void anonymousIsA401WithTheGoldenAndOtherMethodsAreRefused() throws Exception {
        var result = sendForResult(get(PATH));
        MockHttpServletResponse response = result.getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-unauthenticated.json", PATH);
        assertThat(result.getRequest().getSession(false)).as("an anonymous request opens no session").isNull();
        assertThat(response.getHeaders("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
        assertThat(send(adminPost(PATH)).getStatus()).isEqualTo(405);
        Set<String> allowed = new HashSet<>(send(adminPost(PATH)).getHeaders("Allow"));
        assertThat(String.join(",", allowed)).contains("GET");
    }
}
