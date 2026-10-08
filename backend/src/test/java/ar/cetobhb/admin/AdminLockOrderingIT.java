package ar.cetobhb.admin;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.service.AdminOrderService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AOR-07: the admin actions keep the global lock order (order row, then stock rows ascending) while buyer
 * placements, a repeated admin tap and the expiry job hit the same base products with carts in opposite id order,
 * 20 iterations. A path that locked stock rows in another sequence would deadlock (Postgres 40P01) and fail the
 * iteration; a hang fails by timeout. Allowed outcomes: success, {@link IllegalOrderTransitionException} (the loser of
 * a repeated tap) and a lock timeout ({@code CannotAcquireLockException}, the 503 SERVICE_BUSY class).
 *
 * <p>Scope: this proves lock ordering ONLY. The orders the admin pays and cancels here are NOT due (their
 * {@code reserved_until} is after the iteration clock); only the two {@code expiring} orders are, and no admin action
 * targets them. The admin action against the expiry job on the SAME due order is {@link AdminVsExpiryRaceIT}.
 *
 * <p>Characterization (the code existed before the test): evidence by mutation, recorded 2026-10-08. Locking the
 * stock rows in DESCENDING product id in {@code InventoryService.release} (the release done by an admin cancel of a
 * pending order and by the expiry job) made this test fail with Postgres {@code 40P01 deadlock detected}. With the
 * ascending order restored it passes.
 */
class AdminLockOrderingIT extends AbstractPostgresIT {

    private static final int ITERATIONS = 20;

    @Autowired
    private AdminOrderService admin;

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

    /** A lost tap, or a lock timeout (SQLState 55P03). A deadlock (40P01) is also a CannotAcquireLockException. */
    private static boolean allowed(Throwable error) {
        if (error instanceof IllegalOrderTransitionException) {
            return true;
        }
        if (!(error instanceof CannotAcquireLockException)) {
            return false;
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql) {
                return "55P03".equals(sql.getSQLState());
            }
        }
        return false;
    }

    @Test
    void adminActionsPlacementsAndExpiryOnSharedBaseProductsNeverDeadlock() {
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
            long toCancelPending = order(y, x);
            long toCancelPaid = order(z, x);
            lifecycle.markPaid(toCancelPaid);
            clock.set(MutableClock.START.plus(iteration).plus(Duration.ofHours(49)));

            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> {
                admin.pay(toPay);
                return "pay";
            });
            tasks.add(() -> {
                admin.pay(toPay);
                return "pay-again";
            });
            tasks.add(() -> {
                admin.cancel(toCancelPending);
                return "cancel-pending";
            });
            tasks.add(() -> {
                admin.cancel(toCancelPaid);
                return "cancel-paid";
            });
            tasks.add(() -> "expired:" + drain());
            tasks.add(() -> "expired:" + drain());
            for (long[] cart : new long[][] {{x, y}, {y, x}, {z, x}, {y, z}, {x, y, z}, {z, y, x}}) {
                tasks.add(() -> "placed:" + order(cart));
            }

            Report<String> report = BurstRunner.runMeasured(tasks);

            assertThat(report.maxInFlight()).as("iteration %d overlapped", i).isGreaterThan(1);
            assertThat(report.outcomes().stream().filter(o -> !o.succeeded()).map(Outcome::error))
                    .as("iteration %d: only a lost tap or a lock timeout may fail, never a deadlock", i)
                    .allMatch(AdminLockOrderingIT::allowed);
            assertThat(status(toPay)).isEqualTo("PAGADO/-");
            assertThat(status(toCancelPending)).isEqualTo("CANCELADO/CANCELLED_BY_ADMIN");
            assertThat(status(toCancelPaid)).isEqualTo("CANCELADO/CANCELLED_BY_ADMIN");
            assertThat(status(expiring1)).isEqualTo("CANCELADO/EXPIRED");
            assertThat(status(expiring2)).isEqualTo("CANCELADO/EXPIRED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE entity_id = ?"
                    + " AND action = 'ORDER_PAID'", Integer.class, toPay)).isEqualTo(1);
        }

        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }
}
