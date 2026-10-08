package ar.cetobhb.orders;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.orders.service.ExpiryResult;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import ar.cetobhb.support.StockReconciliation.StockDrift;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reservation expiry on a real Postgres with real commits (CLAUDE.md rule 4, REQ-LIF-06). The clock is the
 * {@link MutableClock}; the job itself is never scheduled in tests, the service method is called directly.
 * Every scenario ends with the stock reconciliation clean.
 */
class OrderExpiryIT extends AbstractPostgresIT {

    private static final int BATCH = 100;
    private static final Duration RESERVATION = Duration.ofHours(48);
    @Autowired
    private OrderLifecycleService lifecycle;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private MutableClock clock;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long base(String name, int onHand) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private long order(PlaceOrderCommand.Line... lines) {
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(), List.of(lines)))
                .orderId();
    }

    private static PlaceOrderCommand.Line line(long productId, int quantity) {
        return new PlaceOrderCommand.Line(productId, quantity);
    }

    private int onHand(long productId) {
        return jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class,
                productId);
    }

    private int reserved(long productId) {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class,
                productId);
    }

    private String status(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private void assertStock(long productId, int onHand, int reserved) {
        assertThat(onHand(productId)).as("on_hand").isEqualTo(onHand);
        assertThat(reserved(productId)).as("reserved").isEqualTo(reserved);
    }

    private int expire(int batchSize) {
        return lifecycle.expireDueOrders(batchSize).expired();
    }

    private void at(Duration sinceStart) {
        clock.set(MutableClock.START.plus(sinceStart));
    }

    @Test
    void anOrderExpiresExactlyAtItsReservedUntilAndNotBefore() {
        long b = base("B", 5);
        long id = order(line(b, 2));

        at(Duration.ofHours(47).plusMinutes(59));
        assertThat(expire(BATCH)).isZero();
        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertStock(b, 5, 2);

        at(RESERVATION);
        assertThat(expire(BATCH)).isEqualTo(1);

        assertThat(status(id)).isEqualTo("CANCELADO");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, id))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM orders WHERE id = ?", Timestamp.class, id)
                .toInstant()).isEqualTo(MutableClock.START.plus(RESERVATION));
        assertStock(b, 5, 0);
    }

    @Test
    void repeatedRunsAreIdempotent() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        at(RESERVATION);

        assertThat(expire(BATCH)).isEqualTo(1);
        assertThat(expire(BATCH)).isZero();
        assertThat(expire(BATCH)).isZero();

        assertThat(status(id)).isEqualTo("CANCELADO");
        assertStock(b, 5, 0);
    }

    @Test
    void onlyDuePendingOrdersAreTouched() {
        long b = base("B", 20);
        long paid = order(line(b, 2));
        long dispatched = order(line(b, 1));
        long delivered = order(line(b, 1));
        long cancelled = order(line(b, 1));
        long pending = order(line(b, 3));
        at(Duration.ofHours(1));
        lifecycle.markPaid(paid);
        lifecycle.markPaid(dispatched);
        lifecycle.dispatch(dispatched);
        lifecycle.markPaid(delivered);
        lifecycle.dispatch(delivered);
        lifecycle.deliver(delivered);
        lifecycle.cancel(cancelled, CancelReason.CANCELLED_BY_BUYER);
        assertStock(b, 16, 3);

        at(Duration.ofHours(100));
        assertThat(expire(BATCH)).isEqualTo(1);

        assertThat(status(paid)).isEqualTo("PAGADO");
        assertThat(status(dispatched)).isEqualTo("DESPACHADO");
        assertThat(status(delivered)).isEqualTo("ENTREGADO");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, cancelled))
                .isEqualTo("CANCELLED_BY_BUYER");
        assertThat(status(pending)).isEqualTo("CANCELADO");
        assertStock(b, 16, 0);
    }

    @Test
    void batchesAreBoundedOldestDeadlineFirstAndEachOrderExpiresOnce() {
        long b = base("B", 100);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            at(Duration.ofMinutes(i));
            ids.add(order(line(b, 1)));
        }
        assertStock(b, 100, 12);
        at(Duration.ofHours(100));

        assertThat(expire(5)).isEqualTo(5);
        assertThat(ids.subList(0, 5)).allMatch(id -> status(id).equals("CANCELADO"));
        assertThat(ids.subList(5, 12)).allMatch(id -> status(id).equals("PAGO_PENDIENTE"));
        assertStock(b, 100, 7);

        assertThat(expire(5)).isEqualTo(5);
        assertThat(expire(5)).isEqualTo(2);
        assertThat(expire(5)).isZero();

        assertThat(ids).allMatch(id -> status(id).equals("CANCELADO"));
        assertStock(b, 100, 0);
    }

    @Test
    void aBatchSizeOfExactlyOneIsAcceptedAndExpiresOneOrderPerRun() {
        long b = base("B", 10);
        long first = order(line(b, 1));
        long second = order(line(b, 1));
        at(RESERVATION.plusHours(1));

        assertThat(expire(1)).isEqualTo(1);
        assertThat(status(first)).isEqualTo("CANCELADO");
        assertThat(status(second)).isEqualTo("PAGO_PENDIENTE");

        assertThat(expire(1)).isEqualTo(1);
        assertThat(status(second)).isEqualTo("CANCELADO");
        assertStock(b, 10, 0);
    }

    @Test
    void aBatchSizeBelowOneIsRejected() {
        assertThatThrownBy(() -> expire(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expire(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMultiProductOrderReleasesEveryBaseProductFromItsSnapshot() {
        long x = base("X", 10);
        long y = base("Y", 10);
        long combo = catalog.createCombo(product("Pack", "250.00"), y, 3).id();
        order(line(x, 2), line(combo, 2), line(y, 1));
        assertStock(y, 10, 7);
        // The catalog changes after placement: the release must follow the order snapshot, not the catalog.
        jdbc.update("UPDATE products SET combo_base_units = 9 WHERE id = ?", combo);
        at(RESERVATION);

        assertThat(expire(BATCH)).isEqualTo(1);

        assertStock(x, 10, 0);
        assertStock(y, 10, 0);
    }

    @Test
    void twoConcurrentRunsExpireEachOrderExactlyOnce() {
        long b = base("B", 100);
        for (int i = 0; i < 20; i++) {
            order(line(b, 1));
        }
        at(RESERVATION);
        List<Callable<Integer>> tasks = List.of(() -> drain(5), () -> drain(5));

        Report<Integer> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThan(1);
        assertThat(report.outcomes()).allMatch(Outcome::succeeded);
        assertThat(report.outcomes().stream().mapToInt(Outcome::value).sum()).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE status = 'CANCELADO' AND cancel_reason = 'EXPIRED'",
                Integer.class)).isEqualTo(20);
        assertStock(b, 100, 0);
    }

    private int drain(int batchSize) {
        int total = 0;
        for (int n = expire(batchSize); n > 0; n = expire(batchSize)) {
            total += n;
        }
        return total;
    }

    @Test
    void anOrderLockedByAnotherTransactionIsSkippedWithoutBlocking() {
        long b = base("B", 10);
        long locked = order(line(b, 1));
        long free1 = order(line(b, 1));
        long free2 = order(line(b, 1));
        at(RESERVATION.plusHours(1));

        TransactionTemplate tx = new TransactionTemplate(txManager);
        int expiredWhileLocked = tx.execute(status -> {
            orders.lockById(locked).orElseThrow();
            return CompletableFuture.supplyAsync(() -> expire(BATCH))
                    .orTimeout(10, TimeUnit.SECONDS).join();
        });

        assertThat(expiredWhileLocked).isEqualTo(2);
        assertThat(status(locked)).isEqualTo("PAGO_PENDIENTE");
        assertThat(status(free1)).isEqualTo("CANCELADO");
        assertThat(status(free2)).isEqualTo("CANCELADO");
        assertStock(b, 10, 1);
        assertThat(expire(BATCH)).isEqualTo(1);
        assertStock(b, 10, 0);
    }

    @Test
    void expiryNeverTouchesAPaidOrderEvenPastItsReservedUntil() {
        long b = base("B", 10);
        long paid = order(line(b, 3));
        at(RESERVATION.minusMinutes(1));
        lifecycle.markPaid(paid);
        at(RESERVATION.plusHours(1));

        assertThat(expire(BATCH)).isZero();

        assertThat(status(paid)).isEqualTo("PAGADO");
        assertStock(b, 7, 0);
    }

    @Test
    void payingAnOrderThatAlreadyExpiredFailsAsAnIllegalTransitionAndLeavesTheStockReleased() {
        long b = base("B", 10);
        long expired = order(line(b, 2));
        at(RESERVATION);
        assertThat(expire(BATCH)).isEqualTo(1);

        assertThatThrownBy(() -> lifecycle.markPaid(expired)).isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(status(expired)).isEqualTo("CANCELADO");
        assertStock(b, 10, 0);
    }

    @Test
    void aCorruptOrderRollsTheBatchBackAndTheFallbackExpiresEveryHealthyOrderOneByOne() {
        long healthy = base("H", 10);
        long broken = base("K", 10);
        long h1 = order(line(healthy, 2));
        long corrupt = order(line(broken, 3));
        long h2 = order(line(healthy, 1));
        at(RESERVATION);
        // Corruption: the stock row of one product vanishes, so releasing that order fails (StockNotConfigured).
        jdbc.update("DELETE FROM inventory_stock WHERE product_id = ?", broken);
        try {
            ExpiryResult result = lifecycle.expireDueOrders(BATCH);

            assertThat(result.expiredOrderIds()).containsExactlyInAnyOrder(h1, h2);
            assertThat(result.failedOrderIds()).containsExactly(corrupt);
            assertThat(status(h1)).isEqualTo("CANCELADO");
            assertThat(status(h2)).isEqualTo("CANCELADO");
            assertThat(status(corrupt)).isEqualTo("PAGO_PENDIENTE");
            // Exactly released once: a half-committed batch followed by the fallback would release twice and fail.
            assertStock(healthy, 10, 0);
            assertThat(StockReconciliation.stockDrift(jdbc)).extracting(StockDrift::productId)
                    .containsExactly(broken);
        } finally {
            jdbc.update("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 10, 3)", broken);
        }
    }

    @Test
    void aYoungerHealthyOrderBehindACorruptOldestOrderStillExpiresOnTheNextRun() {
        long healthy = base("H", 10);
        long broken = base("K", 10);
        long corrupt = order(line(broken, 3));
        long h1 = order(line(healthy, 1));
        long h2 = order(line(healthy, 2));
        at(RESERVATION.plusHours(1));
        jdbc.update("DELETE FROM inventory_stock WHERE product_id = ?", broken);
        try {
            ExpiryResult first = lifecycle.expireDueOrders(2);
            assertThat(first.expiredOrderIds()).containsExactly(h1);
            assertThat(first.failedOrderIds()).containsExactly(corrupt);
            assertThat(status(h2)).isEqualTo("PAGO_PENDIENTE");

            ExpiryResult second = lifecycle.expireDueOrders(2);

            assertThat(second.expiredOrderIds()).containsExactly(h2);
            assertThat(second.failedOrderIds()).containsExactly(corrupt);
            assertThat(status(corrupt)).isEqualTo("PAGO_PENDIENTE");
            assertStock(healthy, 10, 0);
        } finally {
            jdbc.update("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 10, 3)", broken);
        }
    }

    @Test
    void expiryRejectsAnActiveCallerTransaction() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        at(RESERVATION);

        assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(status -> expire(BATCH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot run inside an active");

        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertStock(b, 5, 2);
    }

    @Test
    void theFallbackErrorLogCarriesTheExceptionClassAndSqlStateButNoProductName() {
        long healthy = base("H", 10);
        long secret = base("SecretProductName", 10);
        long h1 = order(line(healthy, 1));
        long failing = order(line(secret, 2));
        at(RESERVATION);
        jdbc.execute("CREATE FUNCTION fail_expiry_for_test() RETURNS trigger AS $$ BEGIN"
                + " RAISE EXCEPTION 'secret detail' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_expiry_for_test BEFORE UPDATE ON orders FOR EACH ROW WHEN (OLD.id = "
                + failing + ") EXECUTE FUNCTION fail_expiry_for_test()");
        Logger logger = (Logger) LoggerFactory.getLogger(OrderLifecycleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ExpiryResult result = lifecycle.expireDueOrders(BATCH);

            assertThat(result.expiredOrderIds()).containsExactly(h1);
            assertThat(result.failedOrderIds()).containsExactly(failing);
            List<String> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(errors).singleElement().satisfies(message -> {
                assertThat(message).matches("Could not expire order " + failing + ": \\w+ \\(SQLState 23514\\)");
                assertThat(message).doesNotContain("SecretProductName").doesNotContain("secret detail");
            });
        } finally {
            logger.detachAppender(appender);
            jdbc.execute("DROP TRIGGER fail_expiry_for_test ON orders");
            jdbc.execute("DROP FUNCTION fail_expiry_for_test()");
        }
    }

    @Test
    void theFallbackSkipsAnOrderPaidWhileItWaitedForItsLockInsteadOfFailingIt() {
        long healthy = base("H", 10);
        long broken = base("K", 10);
        long corrupt = order(line(broken, 3));
        long racing = order(line(healthy, 2));
        at(RESERVATION);
        jdbc.update("DELETE FROM inventory_stock WHERE product_id = ?", broken);
        try {
            // A payment transaction holds the racing order: the batch skips it (SKIP LOCKED), locks the corrupt one
            // and fails, so the fallback reads both ids and blocks on the racing order until the payment commits.
            AtomicReference<CompletableFuture<ExpiryResult>> run = new AtomicReference<>();
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                orders.lockById(racing).orElseThrow();
                run.set(CompletableFuture.supplyAsync(() -> lifecycle.expireDueOrders(BATCH)));
                awaitFallbackBlockedOnTheOrderLock();
                lifecycle.markPaid(racing);
            });

            ExpiryResult result = run.get().orTimeout(30, TimeUnit.SECONDS).join();

            assertThat(result.expiredOrderIds()).isEmpty();
            assertThat(result.failedOrderIds()).containsExactly(corrupt);
            assertThat(status(racing)).isEqualTo("PAGADO");
            assertStock(healthy, 8, 0);
        } finally {
            jdbc.update("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 10, 3)", broken);
        }
    }

    /**
     * Polls pg_stat_activity until a backend waits on a lock (no fixed sleeps). Not scoped to a query (a backend
     * blocked on a transaction id reports an empty query text): the payment transaction is the only lock holder in
     * the test, so the only possible waiter is the fallback.
     */
    private void awaitFallbackBlockedOnTheOrderLock() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                            + " AND wait_event_type = 'Lock'",
                    Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("the fallback never blocked on the order row lock within 15s");
    }

    @Test
    void expiryRacingPaymentsOfPastDueOrdersLeavesEachOrderInExactlyOneFinalState() {
        long b = base("B", 100);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(order(line(b, 2)));
        }
        at(RESERVATION.plusHours(1));
        List<Callable<String>> tasks = new ArrayList<>();
        for (long id : ids) {
            tasks.add(() -> {
                lifecycle.markPaid(id);
                return "pay";
            });
        }
        tasks.add(() -> "expired:" + drain(3));
        tasks.add(() -> "expired:" + drain(3));

        Report<String> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThan(1);
        List<Outcome<String>> payments = report.outcomes().subList(0, 10);
        assertThat(report.outcomes().subList(10, 12)).allMatch(Outcome::succeeded);
        assertThat(payments.stream().filter(o -> !o.succeeded()).map(Outcome::error))
                .allMatch(e -> e instanceof IllegalOrderTransitionException);
        long paid = payments.stream().filter(Outcome::succeeded).count();
        int paidRows = jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'PAGADO'", Integer.class);
        int expiredRows = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE status = 'CANCELADO' AND cancel_reason = 'EXPIRED'",
                Integer.class);
        assertThat(paidRows).isEqualTo((int) paid);
        assertThat(paidRows + expiredRows).isEqualTo(10);
        assertStock(b, 100 - 2 * paidRows, 0);
    }

    @Test
    void theRequirementMixedScenarioEndsReconciledWithOnlyTheLastOrderReserved() {
        long b = base("B", 20);
        long a = order(line(b, 2));
        long second = order(line(b, 3));
        lifecycle.markPaid(a);
        lifecycle.cancel(second, CancelReason.CANCELLED_BY_BUYER);
        long c = order(line(b, 4));
        at(RESERVATION);
        assertThat(expire(BATCH)).isEqualTo(1);
        long d = order(line(b, 5));

        assertThat(status(c)).isEqualTo("CANCELADO");
        assertThat(status(d)).isEqualTo("PAGO_PENDIENTE");
        assertStock(b, 18, 5);
        assertThat(jdbc.queryForObject("SELECT reserved_until FROM orders WHERE id = ?", Timestamp.class, d)
                .toInstant()).isEqualTo(MutableClock.START.plus(RESERVATION).plus(RESERVATION));
    }
}
