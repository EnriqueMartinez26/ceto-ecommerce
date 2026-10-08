package ar.cetobhb.admin;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.service.AdminOrderService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.CheckoutInProgressException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.StockReconciliation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AOR-07, an admin cancel against buyer retries of the SAME idempotency key (a double tap on the confirm button while
 * the operator cancels). Every retry must return the existing order ({@code replayed=true}, whatever its status now)
 * or be told to retry ({@link CheckoutInProgressException}, 503 SERVICE_BUSY); nothing else may fail. The order ends
 * cancelled with exactly one ORDER_CANCELLED audit row, the retries never reserve stock again and stock reconciles.
 */
class AdminCancelVsReplayIT extends AbstractPostgresIT {

    private static final int ITERATIONS = 10;
    private static final int REPLAYS = 6;
    private static final int RETRIES = 15;
    private static final int QUANTITY = 2;
    private static final int STOCK = 1000;

    @Autowired
    private AdminOrderService admin;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    @Test
    void anAdminCancelAmongBuyerReplaysOfTheSameKeyLeavesOneCancelledOrderAndOneAuditRow() {
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, STOCK);

        for (int i = 0; i < ITERATIONS; i++) {
            Buyer buyer = freshBuyer();
            PlaceOrderCommand command = new PlaceOrderCommand(UUID.randomUUID().toString(), buyer,
                    List.of(new PlaceOrderCommand.Line(base, QUANTITY)));
            PlacedOrder first = placement.placeOrder(command);
            long id = first.orderId();

            List<Callable<Object>> tasks = new ArrayList<>();
            tasks.add(() -> admin.cancel(id));
            for (int r = 0; r < REPLAYS; r++) {
                tasks.add(() -> {
                    // A buyer hammering the confirm button: many retries per thread, so some land after the cancel
                    // commits. Only the busy signal (503 SERVICE_BUSY) is tolerated; anything else fails the task.
                    List<PlacedOrder> answers = new ArrayList<>();
                    for (int attempt = 0; attempt < RETRIES; attempt++) {
                        try {
                            answers.add(placement.placeOrder(command));
                        } catch (CheckoutInProgressException busy) {
                            // allowed: the retry reached the creator mid-transaction
                        }
                    }
                    return answers;
                });
            }

            Report<Object> report = BurstRunner.runMeasured(tasks);

            assertThat(report.maxInFlight()).as("iteration %d overlapped", i).isGreaterThan(1);
            assertThat(report.outcomes().get(0).succeeded())
                    .as("iteration %d: the admin cancel wins, the replays never change the order: %s", i,
                            report.outcomes().get(0).error())
                    .isTrue();
            for (Outcome<Object> replay : report.outcomes().subList(1, tasks.size())) {
                assertThat(replay.succeeded()).as("iteration %d: only a busy signal may fail a retry: %s", i,
                        replay.error()).isTrue();
                @SuppressWarnings("unchecked")
                List<PlacedOrder> answers = (List<PlacedOrder>) replay.value();
                for (PlacedOrder placed : answers) {
                    assertThat(placed.replayed()).as("iteration %d: a retry returns the existing order", i).isTrue();
                    assertThat(placed.orderId()).isEqualTo(id);
                }
            }
            PlacedOrder afterwards = placement.placeOrder(command);
            assertThat(afterwards.replayed()).as("a retry of a cancelled order is still a replay").isTrue();
            assertThat(afterwards.orderId()).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT status || '/' || cancel_reason FROM orders WHERE id = ?",
                    String.class, id)).isEqualTo("CANCELADO/CANCELLED_BY_ADMIN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = ?::uuid",
                    Integer.class, command.idempotencyKey())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE entity_id = ?"
                    + " AND action = 'ORDER_CANCELLED'", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?",
                    Integer.class, base)).as("iteration %d: reservation released once", i).isZero();
            assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?",
                    Integer.class, base)).isEqualTo(STOCK);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class)).isEqualTo(ITERATIONS);
    }
}
