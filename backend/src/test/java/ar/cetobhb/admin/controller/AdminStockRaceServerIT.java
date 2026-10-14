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
 * adjustments (five +2, five -1), all sent with {@code expectedOnHand} 5 (D-20261008-48), released together through Tomcat,
 * JSON binding, the advice and the response. Purchases never move `on_hand`, so exactly ONE adjustment can apply (the
 * first to take the row lock, unless a -1 is refused for going below `reserved`, in which case the next one tries); every
 * other one is a 409, CONFLICT (stale) or INSUFFICIENT_STOCK (a -1 below `reserved`). A buyer is served (201) or refused
 * for stock (409 INSUFFICIENT_STOCK). There is no 503: the test pool (30) is not smaller than the threads, so a 503 would
 * mean a lock wait or a deadlock that the race must not hide (D-20261008-49). The final counters are exactly the sum of
 * what succeeded, one audit row per applied adjustment, and the reconciliation is clean.
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
            tasks.add(() -> adjust(client, base, "{\"expectedOnHand\":5,\"delta\":2,\"reason\":\"RESTOCK\"}", "plus"));
        }
        for (int i = 0; i < MINUS; i++) {
            tasks.add(() -> adjust(client, base, "{\"expectedOnHand\":5,\"delta\":-1,\"reason\":\"DAMAGED_OR_LOST\"}", "minus"));
        }

        Report<Result> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThanOrEqualTo(tasks.size() / 2);
        assertThat(report.outcomes()).allMatch(Outcome::succeeded);
        List<Result> results = report.outcomes().stream().map(Outcome::value).toList();
        // A 409 is only ever the business refusal: a version conflict or a CHECK violation would be CONFLICT, which is a bug here.
        assertThat(results).extracting(Result::status).as("no 503: a lock wait or a deadlock must not hide in a retry")
                .doesNotContain(503);
        assertThat(results).allMatch(r -> r.status() == 200 || r.status() == 201 || (r.status() == 409
                && (r.body().contains("\"code\":\"CONFLICT\"") && !r.kind().equals("buy")
                        || r.body().contains("\"code\":\"INSUFFICIENT_STOCK\"") && !r.kind().equals("plus"))), "statuses");
        assertThat(count(results, "plus", 200) + count(results, "minus", 200))
                .as("exactly one adjustment applies, all of them expected on_hand 5").isEqualTo(1);
        assertThat(results.stream().filter(r -> r.kind().equals("plus") || r.kind().equals("minus"))
                .filter(r -> r.status() == 409 && r.body().contains("\"code\":\"CONFLICT\"")).count())
                .as("at least one stale adjustment answered CONFLICT").isGreaterThanOrEqualTo(1);
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

    /**
     * The deterministic pair (D-20261008-48): the same body twice at once, as a double tap or a retry would send it.
     * The row lock serialises them and the compare decides: one applies, the other is a 409 CONFLICT.
     */
    @RepeatedTest(5)
    void twoIdenticalAdjustmentsAtOnceApplyOnceAndTheOtherIsAConflict() throws Exception {
        long base = catalog.createBase(product("Pair base", "100.00")).id();
        inventory.createStock(base, 5);
        AdminClient client = loggedInClient();
        List<Callable<Result>> tasks = List.of(
                () -> adjust(client, base, "{\"expectedOnHand\":5,\"delta\":100,\"reason\":\"RESTOCK\"}", "pair"),
                () -> adjust(client, base, "{\"expectedOnHand\":5,\"delta\":100,\"reason\":\"RESTOCK\"}", "pair"));

        Report<Result> report = BurstRunner.runMeasured(tasks);

        assertThat(report.outcomes()).allMatch(Outcome::succeeded);
        List<Result> results = report.outcomes().stream().map(Outcome::value).toList();
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
        assertThat(results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().body())
                .contains("\"code\":\"CONFLICT\"");
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class, base))
                .as("the stock moved once").isEqualTo(105);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class)).isEqualTo(1);
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
