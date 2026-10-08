package ar.cetobhb.admin;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.StockReconciliation;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AINV-05 / INV-A1: the admin stock adjustment locks the stock row like every other mutation, so it cannot oversell or
 * corrupt {@code reserved}. Two deterministic hold-and-probe tests prove the lock (one side holds the row in an open
 * transaction, the other must wait and must then act on the COMMITTED values), one proves the lock timeout path, and a
 * random burst proves the end state under contention: the final counters equal the sum of exactly the operations that
 * succeeded, the CHECK never reaches a caller, and no deadlock (40P01, which Spring also reports as
 * {@code CannotAcquireLockException}) is ever tolerated.
 *
 * <p>Characterization (the code existed before the test). Mutation evidence is recorded in the apply notes.
 */
class StockAdjustBurstIT extends AbstractPostgresIT {

    private static final int ITERATIONS = 15;

    @Autowired
    private AdminInventoryService admin;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long baseWithStock(int onHand) {
        long id = catalog.createBase(product("Frasco " + UUID.randomUUID(), "100.00")).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private void placeOrder(long base, int quantity) {
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(),
                List.of(new PlaceOrderCommand.Line(base, quantity))));
    }

    private int onHand(long base) {
        return jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private int reserved(long base) {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private int auditRows() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
    }

    /** A lock timeout (55P03). A deadlock (40P01) is also a CannotAcquireLockException, so the SQLState decides. */
    private static boolean isLockTimeout(Throwable error) {
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
    void aPlacementWaitsWhileAnAdjustmentHoldsTheRowAndThenSeesItsCommit() throws Exception {
        long base = baseWithStock(5);
        CompletableFuture<Void> placed = new CompletableFuture<>();

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            admin.adjust(base, 10, StockAdjustmentReason.RESTOCK);
            CompletableFuture.runAsync(() -> {
                try {
                    placeOrder(base, 1);
                    placed.complete(null);
                } catch (Throwable e) {
                    placed.completeExceptionally(e);
                }
            });
            assertThatThrownBy(() -> placed.get(1, TimeUnit.SECONDS))
                    .as("the placement must wait for the row the adjustment holds")
                    .isInstanceOf(TimeoutException.class);
        });

        placed.get(10, TimeUnit.SECONDS);
        assertThat(onHand(base)).isEqualTo(15);
        assertThat(reserved(base)).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    void anAdjustmentWaitsForAHeldRowAndThenChecksTheCommittedReservation() throws Exception {
        long base = baseWithStock(5);
        CompletableFuture<Object> adjusted = new CompletableFuture<>();

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // The holder moves 4 units to reserved and keeps the row locked until it commits.
            jdbc.update("UPDATE inventory_stock SET reserved = 4 WHERE product_id = ?", base);
            CompletableFuture.runAsync(() -> {
                try {
                    adjusted.complete(admin.adjust(base, -3, StockAdjustmentReason.DAMAGED_OR_LOST));
                } catch (Throwable e) {
                    adjusted.completeExceptionally(e);
                }
            });
            assertThatThrownBy(() -> adjusted.get(1, TimeUnit.SECONDS))
                    .as("the adjustment must wait for the held row")
                    .isInstanceOf(TimeoutException.class);
        });

        // Having waited, it reads the committed reserved = 4: 5 - 3 = 2 is below it, rejected in Java (not by the CHECK).
        assertThatThrownBy(() -> adjusted.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InsufficientStockException.class);
        assertThat(onHand(base)).isEqualTo(5);
        assertThat(reserved(base)).isEqualTo(4);
        assertThat(auditRows()).isZero();
        jdbc.update("UPDATE inventory_stock SET reserved = 0 WHERE product_id = ?", base);
    }

    @Test
    void aRowHeldPastTheLockTimeoutFailsTheAdjustmentWithNoChangeAndNoAuditRow() throws Exception {
        long base = baseWithStock(5);
        CompletableFuture<Object> adjusted = new CompletableFuture<>();

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE inventory_stock SET on_hand = 5 WHERE product_id = ?", base);
            CompletableFuture.runAsync(() -> {
                try {
                    adjusted.complete(admin.adjust(base, 3, StockAdjustmentReason.RESTOCK));
                } catch (Throwable e) {
                    adjusted.completeExceptionally(e);
                }
            });
            try {
                adjusted.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException | InterruptedException | TimeoutException expected) {
                // Inspected below; the holder only has to outlast the 5 s lock_timeout.
            }
        });

        assertThatThrownBy(adjusted::get).isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(isLockTimeout(e.getCause())).as("55P03: %s", e.getCause()).isTrue());
        assertThat(onHand(base)).isEqualTo(5);
        assertThat(auditRows()).isZero();
    }

    @Test
    void adjustmentsAndPlacementsRacingOnTheLastUnitsEndWithExactlyTheCountersOfWhatSucceeded() {
        for (int i = 0; i < ITERATIONS; i++) {
            long base = baseWithStock(5);
            placeOrder(base, 3);
            List<Callable<String>> tasks = new ArrayList<>();
            for (int n = 0; n < 6; n++) {
                tasks.add(() -> {
                    placeOrder(base, 1);
                    return "placed:1";
                });
            }
            for (int delta : new int[] {-4, -4, 2, 3}) {
                tasks.add(() -> {
                    admin.adjust(base, delta, StockAdjustmentReason.COUNT_CORRECTION);
                    return "adjusted:" + delta;
                });
            }

            Report<String> report = BurstRunner.runMeasured(tasks);

            String context = "iteration " + i;
            assertThat(report.maxInFlight()).as(context).isGreaterThan(1);
            // Only a shortage or a lock timeout may fail: no deadlock, no CHECK violation, no other error.
            assertThat(report.outcomes().stream().filter(o -> !o.succeeded()).map(Outcome::error))
                    .as(context)
                    .allMatch(e -> e instanceof InsufficientStockException || isLockTimeout(e));
            int placed = 0;
            int delta = 0;
            int adjustments = 0;
            for (Outcome<String> outcome : report.outcomes()) {
                if (outcome.succeeded() && outcome.value().startsWith("placed:")) {
                    placed++;
                } else if (outcome.succeeded()) {
                    delta += Integer.parseInt(outcome.value().substring("adjusted:".length()));
                    adjustments++;
                }
            }
            assertThat(reserved(base)).as(context).isEqualTo(3 + placed);
            assertThat(onHand(base)).as(context).isEqualTo(5 + delta);
            assertThat(onHand(base)).as(context).isGreaterThanOrEqualTo(reserved(base));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE entity_id = ?", Integer.class,
                    base)).as(context).isEqualTo(adjustments);
            assertThat(jdbc.queryForObject("SELECT COALESCE(sum(after_value - before_value), 0)"
                    + " FROM admin_audit_log WHERE entity_id = ?", Integer.class, base))
                    .as(context).isEqualTo(delta);
        }
    }
}
