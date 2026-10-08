package ar.cetobhb.admin;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.service.AdminOrderService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.ExpiryResult;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AOR-07, an admin action against the expiry job on the SAME order that is already past its {@code reserved_until}
 * (the clock is moved so the order is due). Whoever takes the order row first wins; the other side must see the
 * committed status (admin: {@link IllegalOrderTransitionException}; job: the row skipped or no longer pending) and
 * touch nothing. Per iteration: exactly one transition wins and the final status says who; an audit row exists only
 * if the admin won; stock reconciles with no drift; the only tolerated failure is a lock timeout (SQLState 55P03),
 * never a deadlock (40P01); nothing else fails.
 *
 * <p>Characterization (the code existed before the test): evidence by mutation, recorded 2026-10-08, see
 * {@code adm-fix3-*} logs and apply-progress.
 */
class AdminVsExpiryRaceIT extends AbstractPostgresIT {

    private static final int ITERATIONS = 25;
    private static final int QUANTITY = 2;
    private static final int STOCK = 1000;

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

    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long dueOrder(long base, int iteration) {
        clock.set(MutableClock.START.plus(Duration.ofHours(200L * iteration)));
        long id = placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(),
                List.of(new PlaceOrderCommand.Line(base, QUANTITY)))).orderId();
        clock.set(MutableClock.START.plus(Duration.ofHours(200L * iteration + 49)));
        return id;
    }

    private String status(long orderId) {
        return jdbc.queryForObject("SELECT status || '/' || COALESCE(cancel_reason, '-') FROM orders WHERE id = ?",
                String.class, orderId);
    }

    private int audit(long orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE entity_id = ?", Integer.class,
                orderId);
    }

    private int stock(String column, long base) {
        return jdbc.queryForObject("SELECT " + column + " FROM inventory_stock WHERE product_id = ?", Integer.class,
                base);
    }

    /** A lost race (illegal transition) or a lock timeout by SQLState 55P03; a deadlock 40P01 is not allowed. */
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

    private void race(String adminStatus, LongFunction<Callable<String>> adminAction) {
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, STOCK);
        int adminWins = 0;
        int expiryWins = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            long id = dueOrder(base, i);
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(adminAction.apply(id));
            tasks.add(() -> {
                ExpiryResult result = lifecycle.expireDueOrders(5);
                assertThat(result.failed()).as("the job reports no failed order").isZero();
                return "expired:" + result.expired();
            });

            Report<String> report = BurstRunner.runMeasured(tasks);

            Outcome<String> adminOutcome = report.outcomes().get(0);
            Outcome<String> jobOutcome = report.outcomes().get(1);
            assertThat(report.maxInFlight()).as("iteration %d overlapped", i).isGreaterThan(1);
            assertThat(jobOutcome.succeeded()).as("iteration %d: the job never fails: %s", i, jobOutcome.error())
                    .isTrue();
            if (!adminOutcome.succeeded()) {
                assertThat(allowed(adminOutcome.error())).as("iteration %d: admin failure %s", i, adminOutcome.error())
                        .isTrue();
            }
            String finalStatus = status(id);
            assertThat(finalStatus).as("iteration %d: exactly one side wins", i)
                    .isIn(adminStatus, "CANCELADO/EXPIRED");
            boolean adminWon = finalStatus.equals(adminStatus);
            assertThat(adminOutcome.succeeded()).as("iteration %d: admin success iff its status stuck", i)
                    .isEqualTo(adminWon);
            assertThat(jobOutcome.value()).as("iteration %d: the job expired it iff it won", i)
                    .isEqualTo("expired:" + (adminWon ? 0 : 1));
            assertThat(audit(id)).as("iteration %d: an audit row exists only if the admin won", i)
                    .isEqualTo(adminWon ? 1 : 0);
            if (adminWon) {
                adminWins++;
            } else {
                expiryWins++;
            }
            int expectedOnHand = adminStatus.startsWith("PAGADO") ? STOCK - QUANTITY * adminWins : STOCK;
            assertThat(stock("on_hand", base)).as("iteration %d on_hand", i).isEqualTo(expectedOnHand);
            assertThat(stock("reserved", base)).as("iteration %d reserved", i).isZero();
            assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
        }

        assertThat(adminWins + expiryWins).isEqualTo(ITERATIONS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class)).isEqualTo(adminWins);
    }

    /**
     * Deterministic half of the race: the admin action holds the order row (its transaction is open) while the job
     * runs on another thread. The job must skip the row at once (SKIP LOCKED) and report nothing expired or failed; a
     * job that read the row without the lock would wait for it and miss the deadline asserted here.
     */
    private void expiryRunsWhileAdminHoldsTheRow(Callable<String> adminAction, long id, String adminStatus) {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                try {
                    adminAction.call();
                    long started = System.nanoTime();
                    ExpiryResult during = other.submit(() -> lifecycle.expireDueOrders(5)).get(15, TimeUnit.SECONDS);
                    assertThat(during.expired()).as("the row is held: nothing to expire").isZero();
                    assertThat(during.failed()).as("a held row is skipped, not a failure").isZero();
                    assertThat(Duration.ofNanos(System.nanoTime() - started)).as("the job did not wait for the row")
                            .isLessThan(Duration.ofSeconds(3));
                } catch (InterruptedException | java.util.concurrent.ExecutionException
                        | java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("the job did not finish while the admin held the row", e);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            other.shutdownNow();
        }

        assertThat(status(id)).isEqualTo(adminStatus);
        assertThat(audit(id)).isEqualTo(1);
        assertThat(lifecycle.expireDueOrders(5).expired()).as("nothing left to expire after the commit").isZero();
    }

    @Test
    void theJobSkipsADueOrderWhileAnAdminPaymentHoldsItsRow() {
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, STOCK);
        long id = dueOrder(base, 0);

        expiryRunsWhileAdminHoldsTheRow(() -> {
            admin.pay(id);
            return "pay";
        }, id, "PAGADO/-");

        assertThat(stock("on_hand", base)).isEqualTo(STOCK - QUANTITY);
        assertThat(stock("reserved", base)).isZero();
    }

    @Test
    void theJobSkipsADueOrderWhileAnAdminCancellationHoldsItsRow() {
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, STOCK);
        long id = dueOrder(base, 0);

        expiryRunsWhileAdminHoldsTheRow(() -> {
            admin.cancel(id);
            return "cancel";
        }, id, "CANCELADO/CANCELLED_BY_ADMIN");

        assertThat(stock("on_hand", base)).isEqualTo(STOCK);
        assertThat(stock("reserved", base)).isZero();
    }

    @Test
    void payAgainstExpiryOfTheSameDueOrderHasExactlyOneWinner() {
        race("PAGADO/-", id -> () -> {
            admin.pay(id);
            return "pay";
        });
    }

    @Test
    void cancelAgainstExpiryOfTheSameDueOrderHasExactlyOneWinner() {
        race("CANCELADO/CANCELLED_BY_ADMIN", id -> () -> {
            admin.cancel(id);
            return "cancel";
        });
    }
}
