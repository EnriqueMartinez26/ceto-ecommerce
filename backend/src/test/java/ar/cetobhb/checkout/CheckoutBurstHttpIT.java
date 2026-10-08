package ar.cetobhb.checkout;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractServerIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.StockReconciliation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Concurrency over real HTTP (REQ-CHK-04, CLAUDE.md section 8): a real server port, real commits, requests released
 * together by a start latch. Each burst asserts that requests genuinely overlapped. Unlike the service-level bursts,
 * this goes through Tomcat threads, JSON binding, the advice and the response, so a 5xx anywhere fails the test.
 */
class CheckoutBurstHttpIT extends AbstractServerIT {

    private static final int N = 20;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    private long base(int onHand) {
        long id = catalog.createBase(product("Burst base", "100.00")).id();
        inventory.createStock(id, onHand);
        return id;
    }

    /** Distinct valid mobile number per request, so a per-phone cap (S4a) never interferes with these bursts. */
    private static String body(long productId, int quantity, int phoneSuffix) {
        return """
                {"buyer":{"name":"Maria Perez","phone":"+549351555%04d","address":"Calle Falsa 123",
                          "zone":"CORDOBA_CAPITAL"},
                 "lines":[{"productId":%d,"quantity":%d}]}
                """.formatted(phoneSuffix, productId, quantity);
    }

    private long reserved(long productId) {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Long.class,
                productId);
    }

    private long orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    @Test
    void theLastUnitGoesToExactlyOneOfManyConcurrentHttpBuyers() {
        long b = base(1);
        List<Callable<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String payload = body(b, 1, i);
            tasks.add(() -> post(UUID.randomUUID().toString(), payload));
        }

        Report<Reply> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThan(1);
        assertThat(report.outcomes()).allMatch(Outcome::succeeded);
        List<Reply> replies = report.outcomes().stream().map(Outcome::value).toList();
        assertThat(replies).noneMatch(r -> r.status() >= 500);
        assertThat(replies.stream().filter(r -> r.status() == 201)).hasSize(1);
        List<Reply> rejected = replies.stream().filter(r -> r.status() != 201).toList();
        assertThat(rejected).hasSize(N - 1).allMatch(r -> r.status() == 409);
        assertThat(rejected).allMatch(r -> code(r).equals("INSUFFICIENT_STOCK"));
        assertThat(reserved(b)).isEqualTo(1L);
        assertThat(orderCount()).isEqualTo(1L);
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    @Test
    void manyConcurrentRequestsWithTheSameKeyCreateOneOrderAndReplayTheRest() {
        long b = base(10);
        String key = UUID.randomUUID().toString();
        String payload = body(b, 1, 0);
        List<Callable<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            tasks.add(() -> post(key, payload));
        }

        Report<Reply> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThan(1);
        assertThat(report.outcomes()).allMatch(Outcome::succeeded);
        List<Reply> replies = report.outcomes().stream().map(Outcome::value).toList();
        assertThat(replies).noneMatch(r -> r.status() >= 500);
        assertThat(replies.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(replies.stream().filter(r -> r.status() == 200)).hasSize(N - 1);
        assertThat(replies.stream().map(r -> parse(r).get("orderNumber").asString()).distinct()).hasSize(1);
        assertThat(replies.stream().filter(r -> r.status() == 200))
                .allMatch(r -> parse(r).get("replayed").asBoolean());
        assertThat(orderCount()).isEqualTo(1L);
        assertThat(reserved(b)).isEqualTo(1L);
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private JsonNode parse(Reply reply) {
        return json.readTree(reply.body());
    }

    private String code(Reply reply) {
        return parse(reply).get("code").asString();
    }
}
