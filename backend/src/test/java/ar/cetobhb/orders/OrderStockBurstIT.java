package ar.cetobhb.orders;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.StockReconciliation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrency on stock with real commits (no @Transactional on the test): CLAUDE.md rules 1 and 3. The last-unit
 * burst proves no oversell and a correct {@code reserved} (rule 1); by itself it does not prove the row lock, since
 * {@code @Version} and the stock CHECK also stop an oversell. The test that fails without {@code FOR UPDATE} is the
 * opposite-order one (S9b mutation evidence); the ascending lock ORDER specifically stays pending for S11 (rule 3).
 * Every burst asserts that more than one task was in flight at once. The Hikari pool of the test profile is larger
 * than the thread count, so contention happens on row locks, never on the pool.
 */
class OrderStockBurstIT extends AbstractPostgresIT {

    private static final int THREADS = 12;
    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private long base(String name, int onHand) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, onHand);
        return id;
    }

    /** A fresh-key order of one unit of each given product, in the given line order. */
    private Callable<PlacedOrder> placing(long... productIds) {
        List<PlaceOrderCommand.Line> lines =
                Arrays.stream(productIds).mapToObj(id -> new PlaceOrderCommand.Line(id, 1)).toList();
        PlaceOrderCommand command = new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(), lines);
        return () -> placement.placeOrder(command);
    }

    private int stockColumn(String column, long productId) {
        return jdbc.queryForObject("SELECT " + column + " FROM inventory_stock WHERE product_id = ?", Integer.class,
                productId);
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    @Test
    void lastUnitGoesToExactlyOneOfManyConcurrentBuyers() {
        long b = base("Base", 1);
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(placing(b));
        }

        Report<PlacedOrder> report = BurstRunner.runMeasured(tasks);
        List<Outcome<PlacedOrder>> outcomes = report.outcomes();
        assertThat(report.maxInFlight()).isGreaterThan(1);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::error))
                .hasSize(THREADS - 1).allMatch(e -> e instanceof InsufficientStockException);
        assertThat(stockColumn("reserved", b)).isEqualTo(1);
        assertThat(stockColumn("on_hand", b)).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    @Test
    void oppositeOrderMultiProductCartsNeverDeadlock() {
        long a = base("A", 100);
        long b = base("B", 100);
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(i % 2 == 0 ? placing(a, b) : placing(b, a));
        }

        Report<PlacedOrder> report = BurstRunner.runMeasured(tasks);
        List<Outcome<PlacedOrder>> outcomes = report.outcomes();
        assertThat(report.maxInFlight()).isGreaterThan(1);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(stockColumn("reserved", a)).isEqualTo(THREADS);
        assertThat(stockColumn("reserved", b)).isEqualTo(THREADS);
        assertThat(orderCount()).isEqualTo(THREADS);
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }
}
