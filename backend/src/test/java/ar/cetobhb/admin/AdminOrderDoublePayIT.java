package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.service.AdminOrderService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BuyerFixtures;
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
 * AOR-05, a repeated tap or two admin sessions: concurrent {@code pay} on ONE pending order must give exactly one
 * success, the others {@link IllegalOrderTransitionException}, stock committed once and one audit row.
 *
 * <p>Characterization (the code existed before the test): evidence by mutation, recorded 2026-10-08. Changing
 * {@code OrderLifecycleService.lock} to a plain {@code findById} (no {@code FOR UPDATE} on the order row) made this
 * test fail: the losers no longer meet an illegal transition. Restored, the test passes.
 */
class AdminOrderDoublePayIT extends AbstractPostgresIT {

    private static final int ITERATIONS = 10;
    private static final int TAPS = 6;

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
    void concurrentPaymentsOfOneOrderGiveExactlyOneSuccessAndOneAuditRow() {
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 1000);

        for (int i = 0; i < ITERATIONS; i++) {
            long id = placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                    BuyerFixtures.freshBuyer(), List.of(new PlaceOrderCommand.Line(base, 2)))).orderId();
            int onHandBefore = jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?",
                    Integer.class, base);
            List<Callable<Long>> taps = new ArrayList<>();
            for (int t = 0; t < TAPS; t++) {
                taps.add(() -> admin.pay(id).id());
            }

            Report<Long> report = BurstRunner.runMeasured(taps);

            assertThat(report.maxInFlight()).as("iteration %d overlapped", i).isGreaterThan(1);
            assertThat(report.outcomes().stream().filter(Outcome::succeeded)).as("iteration %d successes", i)
                    .hasSize(1);
            assertThat(report.outcomes().stream().filter(o -> !o.succeeded()).map(Outcome::error))
                    .as("iteration %d: the losers see an illegal transition, nothing else", i)
                    .hasSize(TAPS - 1)
                    .allMatch(e -> e instanceof IllegalOrderTransitionException);
            assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?",
                    Integer.class, base)).as("iteration %d: stock committed once", i).isEqualTo(onHandBefore - 2);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM admin_audit_log WHERE action = 'ORDER_PAID' AND entity_id = ?",
                    Integer.class, id)).as("iteration %d: one audit row", i).isEqualTo(1);
        }

        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class,
                base)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class))
                .isEqualTo(ITERATIONS);
    }
}
