package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.StockReconciliation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Adjustments racing buyer purchases over real HTTP (AINV-05): stock (5, 0), twenty buyers of one unit each and ten
 * adjustments (five +2, five -1) released together through Tomcat, JSON binding, the advice and the response. A buyer may
 * be served (201), refused for stock (409) or told to retry (503); an adjustment may apply (200), be refused because it
 * would leave {@code on_hand} below {@code reserved} (409) or be told to retry (503); nothing else, and never a 5xx
 * other than that 503. The final counters are exactly the sum of what succeeded: {@code on_hand = 5 + 2a - b} and
 * {@code reserved = purchases}, one audit row per applied adjustment, and the reconciliation is clean. The invariant of
 * the table (reserved never above on_hand) is also enforced by its CHECK, so a violation would show as a 500.
 */
class AdminStockRaceServerIT extends AbstractAdminServerIT {

    private static final int BUYERS = 20;
    private static final int PLUS = 5;
    private static final int MINUS = 5;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private record Result(String kind, int status, String body) {
    }

    private static String body(long productId, int phoneSuffix) {
        return """
                {"buyer":{"name":"Maria Perez","phone":"+549351555%04d","address":"Calle Falsa 123",
                          "zone":"CORDOBA_CAPITAL"},
                 "lines":[{"productId":%d,"quantity":1}]}
                """.formatted(phoneSuffix, productId);
    }

    @RepeatedTest(5)
    void adjustmentsRacingPurchasesLeaveCountersEqualToTheSumOfWhatSucceeded() throws Exception {
        long base = catalog.createBase(product("Race base", "100.00")).id();
        inventory.createStock(base, 5);
        AdminClient client = loggedInClient();
        List<Callable<Result>> tasks = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            String payload = body(base, i);
            tasks.add(() -> {
                Reply reply = post(UUID.randomUUID().toString(), payload);
                return new Result("buy", reply.status(), reply.body());
            });
        }
        for (int i = 0; i < PLUS; i++) {
            tasks.add(() -> adjust(client, base, "{\"delta\":2,\"reason\":\"RESTOCK\"}", "plus"));
        }
        for (int i = 0; i < MINUS; i++) {
            tasks.add(() -> adjust(client, base, "{\"delta\":-1,\"reason\":\"DAMAGED_OR_LOST\"}", "minus"));
        }

        Report<Result> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThanOrEqualTo(tasks.size() / 2);
        assertThat(report.outcomes()).allMatch(Outcome::succeeded);
        List<Result> results = report.outcomes().stream().map(Outcome::value).toList();
        // A 409 is only ever the business refusal: a version conflict or a CHECK violation would be CONFLICT, which is a bug here.
        assertThat(results).allMatch(r -> r.status() == 200 || r.status() == 201 || r.status() == 503
                || (r.status() == 409 && r.body().contains("\"code\":\"INSUFFICIENT_STOCK\"")
                        && !r.kind().equals("plus")), "statuses");
        long purchases = count(results, "buy", 201);
        long applied = count(results, "plus", 200) * 2 - count(results, "minus", 200);
        long onHand = jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Long.class, base);
        long reserved = jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Long.class,
                base);
        assertThat(onHand).as("on_hand = 5 plus the adjustments that were applied").isEqualTo(5 + applied);
        assertThat(reserved).as("reserved = the purchases that were served").isEqualTo(purchases);
        assertThat(reserved).isLessThanOrEqualTo(onHand);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(purchases);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Long.class))
                .isEqualTo(count(results, "plus", 200) + count(results, "minus", 200));
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private Result adjust(AdminClient client, long productId, String payload, String kind) throws Exception {
        AdminClient.Reply reply = client.send("POST", "/api/admin/stock/" + productId + "/adjust",
                "application/json", payload, client.csrfHeader());
        return new Result(kind, reply.status(), reply.body());
    }

    private static long count(List<Result> results, String kind, int status) {
        return results.stream().filter(r -> r.kind().equals(kind) && r.status() == status).count();
    }
}
