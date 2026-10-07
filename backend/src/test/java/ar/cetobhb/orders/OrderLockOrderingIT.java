package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
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
 * The burst is only a proof if the guard bites: see the mutation evidence in the S11 apply-progress (stock locking
 * made descending in {@code InventoryService.release} produced deadlocks here).
 */
class OrderLockOrderingIT extends AbstractPostgresIT {

    private static final int ITERATIONS = 20;
    private static final Buyer BUYER =
            new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);

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
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), BUYER, lines)).orderId();
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
        assertThat(jdbc.queryForObject("SELECT max(reserved) FROM inventory_stock", Integer.class))
                .isLessThanOrEqualTo(12);
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
