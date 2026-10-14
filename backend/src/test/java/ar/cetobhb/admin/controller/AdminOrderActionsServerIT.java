package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.StockReconciliation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The double tap through real HTTP (AOR-05, AUTH-12): one logged-in browser fires the same action twice at once. The
 * order row lock serializes them, so exactly one wins (200) and the other meets the illegal edge (409
 * ILLEGAL_TRANSITION); the stock moves once and exactly one audit row exists. A real session and a real CSRF token,
 * because the MockMvc tests cannot prove the concurrency.
 *
 * <p>Characterization (the code existed before the test): evidence by mutation, recorded in the apply notes. Reading
 * the order in {@code OrderLifecycleService.lock} without {@code FOR UPDATE} makes the losers fail with a different
 * answer than the illegal transition, and this test fails.
 */
class AdminOrderActionsServerIT extends AbstractAdminServerIT {

    private static final int ITERATIONS = 8;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JdbcTemplate jdbc;

    private long base;
    private int phoneSuffix;

    @BeforeEach
    void seed() {
        base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 100);
        phoneSuffix = 0;
    }

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long order(int quantity) {
        String phone = "+549351555" + String.format("%04d", ++phoneSuffix);
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer("Ana Gomez", phone, "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, quantity)))).orderId();
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private List<AdminClient.Reply> tap(AdminClient client, String path, int taps) {
        List<Callable<AdminClient.Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < taps; i++) {
            tasks.add(() -> client.postWithToken(path));
        }
        List<AdminClient.Reply> replies = new ArrayList<>();
        for (Outcome<AdminClient.Reply> outcome : BurstRunner.run(tasks)) {
            assertThat(outcome.succeeded()).as("no transport failure: %s", outcome.error()).isTrue();
            replies.add(outcome.value());
        }
        return replies;
    }

    @Test
    void twoConcurrentPaysGiveExactlyOne200AndOne409WithTheStockMovedOnceAndOneAuditRow() throws Exception {
        AdminClient client = loggedInClient();
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            long id = order(3);
            int before = onHand();
            int auditBefore = jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
            String path = "/api/admin/orders/" + id + "/pay";

            List<AdminClient.Reply> replies = tap(client, path, 2);

            assertThat(replies).extracting(AdminClient.Reply::status).as("iteration %d", iteration)
                    .containsExactlyInAnyOrder(200, 409);
            AdminClient.Reply loser = replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
            ContractAssertions.assertMatchesGoldenForPath(loser.body(), loser.header("x-request-id"),
                    "admin-error-illegal-transition.json", path);
            assertThat(loser.body()).doesNotContain("Ana Gomez").doesNotContain("Calle Falsa")
                    .doesNotContain("55500").doesNotContain("PAGADO");
            assertThat(onHand()).as("stock decremented once").isEqualTo(before - 3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE entity_id = ? "
                    + "AND action = 'ORDER_PAID'", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class))
                    .isEqualTo(auditBefore + 1);
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id))
                    .isEqualTo("PAGADO");
        }
    }

    @Test
    void sixConcurrentCancelsOfOnePaidOrderGiveOne200AndFive409AndTheUnitsReturnOnce() throws Exception {
        AdminClient client = loggedInClient();
        long id = order(4);
        assertThat(client.postWithToken("/api/admin/orders/" + id + "/pay").status()).isEqualTo(200);
        int paidOnHand = onHand();

        List<AdminClient.Reply> replies = tap(client, "/api/admin/orders/" + id + "/cancel", 6);

        assertThat(replies).extracting(AdminClient.Reply::status).containsOnly(200, 409);
        assertThat(replies.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(onHand()).isEqualTo(paidOnHand + 4);
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log ORDER BY id", String.class))
                .containsExactly("ORDER_PAID", "ORDER_CANCELLED");
    }

    @Test
    void anActionWithoutTheCsrfHeaderIsA403OverTheRealServerAndChangesNothing() throws Exception {
        AdminClient client = loggedInClient();
        long id = order(1);

        AdminClient.Reply reply = client.send("POST", "/api/admin/orders/" + id + "/pay", null, null, Map.of());

        assertThat(reply.status()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-forbidden.json", "/api/admin/orders/" + id + "/pay");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id))
                .isEqualTo("PAGO_PENDIENTE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class)).isZero();
    }
}
