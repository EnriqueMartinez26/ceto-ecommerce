package ar.cetobhb.orders;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StaleStockException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Global lock order under real concurrency (CLAUDE.md section 4, rule 3, REQ-LIF-08): order rows first, then stock
 * rows ascending by base product id, in EVERY path (placement, payment, cancellation, expiry). Each iteration fires
 * multi-product orders that list their lines in opposite id order, a payment, a cancellation and two expiry runs
 * on the same base products at once. Any path that locked stock rows in another sequence would deadlock with the
 * others and Postgres would abort one transaction (40P01), which fails the iteration; a hang fails by timeout.
 *
 * <p>Mutation evidence (recorded 2026-10-07): the burst is only a proof if the guard bites. Changing
 * {@code InventoryService.release} to lock the stock rows in DESCENDING product id (instead of ascending) made
 * {@code placementsPaymentsCancellationsAndExpiryOnSharedBaseProductsNeverDeadlock} fail at iteration 0 with
 * Postgres {@code 40P01 deadlock detected}. With the ascending order restored the test is green.
 *
 * <p>The big burst also runs concurrent {@code InventoryService.adjustBy} calls (INV-A1). Mutation evidence
 * (2026-10-08): making {@code adjustBy} also lock the stock row of {@code productId - 1} AFTER its own (a descending
 * second lock) made the burst fail with {@code 40P01}; without the mutation it is green.
 */
class OrderLockOrderingIT extends AbstractPostgresIT {

    private static final int ITERATIONS = 20;
    @Autowired
    private OrderLifecycleService lifecycle;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long base(String name) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, 5000);
        return id;
    }

    private long order(long... products) {
        List<PlaceOrderCommand.Line> lines = new ArrayList<>();
        for (long product : products) {
            lines.add(new PlaceOrderCommand.Line(product, 1));
        }
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(), lines)).orderId();
    }

    private String status(long orderId) {
        return jdbc.queryForObject("SELECT status || '/' || COALESCE(cancel_reason, '-') FROM orders WHERE id = ?",
                String.class, orderId);
    }

    private int drain() {
        int total = 0;
        for (int n = lifecycle.expireDueOrders(3).expired(); n > 0; n = lifecycle.expireDueOrders(3).expired()) {
            total += n;
        }
        return total;
    }

    @Test
    void anExpiryBatchRacingPlacementsOnTheSameTightStockEndsConsistent() {
        long x = catalog.createBase(product("X", "100.00")).id();
        long y = catalog.createBase(product("Y", "100.00")).id();
        inventory.createStock(x, 12);
        inventory.createStock(y, 12);
        for (int i = 0; i < 10; i++) {
            order(x, y);
        }
        clock.set(MutableClock.START.plus(Duration.ofHours(49)));
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> "expired:" + drain());
        tasks.add(() -> "expired:" + drain());
        for (int i = 0; i < 8; i++) {
            long[] cart = i % 2 == 0 ? new long[] {y, x} : new long[] {x, y};
            tasks.add(() -> "placed:" + order(cart));
        }

        Report<String> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThan(1);
        assertThat(report.outcomes().stream().filter(o -> !o.succeeded()).map(Outcome::error))
                .allMatch(e -> e instanceof InsufficientStockException);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE cancel_reason = 'EXPIRED'", Integer.class))
                .isEqualTo(10);
        // Exact final state: every old order expired, so what stays reserved is exactly the lines of the placements
        // that fit (one unit of X and one of Y each), and no unit ever left the shelf.
        long placed = report.outcomes().stream().filter(Outcome::succeeded)
                .filter(o -> o.value().startsWith("placed:")).count();
        assertThat(placed).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'PAGO_PENDIENTE'", Integer.class))
                .isEqualTo((int) placed);
        for (long product : new long[] {x, y}) {
            assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?",
                    Integer.class, product)).as("reserved of %d", product).isEqualTo((int) placed);
            assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?",
                    Integer.class, product)).as("on_hand of %d", product).isEqualTo(12);
        }
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    /**
     * The adjustment sends the on_hand it read, as the panel does (D-20261008-48). A payment may move on_hand between
     * the read and the lock; then the compare under the lock loses with a stale answer, which is a normal outcome here:
     * the lock was taken first either way, so the lock order is exercised in both.
     */
    private String adjustingWhatItSaw(long product, int delta) {
        int seen = jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class,
                product);
        try {
            return "adjusted:" + inventory.adjustBy(product, delta, seen).productId();
        } catch (StaleStockException stale) {
            return "stale:" + product;
        }
    }

    @Test
    void placementsPaymentsCancellationsAndExpiryOnSharedBaseProductsNeverDeadlock() {
        long x = base("X");
        long y = base("Y");
        long z = base("Z");
        assertThat(x).isLessThan(y);
        assertThat(y).isLessThan(z);

        for (int i = 0; i < ITERATIONS; i++) {
            Duration iteration = Duration.ofHours(100L * i);
            clock.set(MutableClock.START.plus(iteration));
            long expiring1 = order(x, y, z);
            long expiring2 = order(z, y, x);
            clock.set(MutableClock.START.plus(iteration).plus(Duration.ofHours(2)));
            long toPay = order(x, y);
            long toCancel = order(y, x);
            // Expiring orders are due, the two others (deadline +50 h) are not.
            clock.set(MutableClock.START.plus(iteration).plus(Duration.ofHours(49)));

            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> {
                lifecycle.markPaid(toPay);
                return "pay";
            });
            tasks.add(() -> {
                lifecycle.cancel(toCancel, CancelReason.CANCELLED_BY_BUYER);
                return "cancel";
            });
            tasks.add(() -> "expired:" + drain());
            tasks.add(() -> "expired:" + drain());
            for (long[] cart : new long[][] {{x, y}, {y, x}, {z, x}, {y, z}, {x, y, z}, {z, y, x}}) {
                tasks.add(() -> "placed:" + order(cart));
            }
            // INV-A1: the admin delta adjustment (AINV-05) takes a single stock row lock and nothing else, so it adds no
            // lock-order edge. One per product, in the middle of the others: a version that held a second row (or
            // locked in another sequence) would deadlock with the ascending lockers and fail the iteration.
            tasks.add(() -> adjustingWhatItSaw(z, 1));
            tasks.add(() -> adjustingWhatItSaw(y, -1));
            tasks.add(() -> adjustingWhatItSaw(x, 1));

            Report<String> report = BurstRunner.runMeasured(tasks);

            assertThat(report.maxInFlight()).as("iteration %d overlapped", i).isGreaterThan(1);
            assertThat(report.outcomes().stream().filter(o -> !o.succeeded()).map(Outcome::error))
                    .as("iteration %d: no deadlock, no lock failure, nothing unexpected", i).isEmpty();
            assertThat(status(toPay)).isEqualTo("PAGADO/-");
            assertThat(status(toCancel)).isEqualTo("CANCELADO/CANCELLED_BY_BUYER");
            assertThat(status(expiring1)).isEqualTo("CANCELADO/EXPIRED");
            assertThat(status(expiring2)).isEqualTo("CANCELADO/EXPIRED");
        }

        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }
}
