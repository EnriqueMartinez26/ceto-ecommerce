package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
    private static final Buyer BUYER =
            new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);

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
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), BUYER, List.of(lines)))
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

    private void at(Duration sinceStart) {
        clock.set(MutableClock.START.plus(sinceStart));
    }

    @Test
    void anOrderExpiresExactlyAtItsReservedUntilAndNotBefore() {
        long b = base("B", 5);
        long id = order(line(b, 2));

        at(Duration.ofHours(47).plusMinutes(59));
        assertThat(lifecycle.expireDueOrders(BATCH)).isZero();
        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertStock(b, 5, 2);

        at(RESERVATION);
        assertThat(lifecycle.expireDueOrders(BATCH)).isEqualTo(1);

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

        assertThat(lifecycle.expireDueOrders(BATCH)).isEqualTo(1);
        assertThat(lifecycle.expireDueOrders(BATCH)).isZero();
        assertThat(lifecycle.expireDueOrders(BATCH)).isZero();

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
        assertThat(lifecycle.expireDueOrders(BATCH)).isEqualTo(1);

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

        assertThat(lifecycle.expireDueOrders(5)).isEqualTo(5);
        assertThat(ids.subList(0, 5)).allMatch(id -> status(id).equals("CANCELADO"));
        assertThat(ids.subList(5, 12)).allMatch(id -> status(id).equals("PAGO_PENDIENTE"));
        assertStock(b, 100, 7);

        assertThat(lifecycle.expireDueOrders(5)).isEqualTo(5);
        assertThat(lifecycle.expireDueOrders(5)).isEqualTo(2);
        assertThat(lifecycle.expireDueOrders(5)).isZero();

        assertThat(ids).allMatch(id -> status(id).equals("CANCELADO"));
        assertStock(b, 100, 0);
    }

    @Test
    void aBatchSizeBelowOneIsRejected() {
        assertThatThrownBy(() -> lifecycle.expireDueOrders(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.expireDueOrders(-1)).isInstanceOf(IllegalArgumentException.class);
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

        assertThat(lifecycle.expireDueOrders(BATCH)).isEqualTo(1);

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
        for (int n = lifecycle.expireDueOrders(batchSize); n > 0; n = lifecycle.expireDueOrders(batchSize)) {
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
            return CompletableFuture.supplyAsync(() -> lifecycle.expireDueOrders(BATCH))
                    .orTimeout(10, TimeUnit.SECONDS).join();
        });

        assertThat(expiredWhileLocked).isEqualTo(2);
        assertThat(status(locked)).isEqualTo("PAGO_PENDIENTE");
        assertThat(status(free1)).isEqualTo("CANCELADO");
        assertThat(status(free2)).isEqualTo("CANCELADO");
        assertStock(b, 10, 1);
        assertThat(lifecycle.expireDueOrders(BATCH)).isEqualTo(1);
        assertStock(b, 10, 0);
    }

    @Test
    void payingAfterExpiryFailsAndExpiryNeverTouchesAPaidOrder() {
        long b = base("B", 10);
        long expired = order(line(b, 2));
        long paid = order(line(b, 3));
        at(RESERVATION.minusMinutes(1));
        lifecycle.markPaid(paid);
        at(RESERVATION);

        assertThat(lifecycle.expireDueOrders(BATCH)).isEqualTo(1);

        assertThatThrownBy(() -> lifecycle.markPaid(expired)).isInstanceOf(IllegalOrderTransitionException.class);
        assertThat(status(paid)).isEqualTo("PAGADO");
        assertThat(status(expired)).isEqualTo("CANCELADO");
        assertStock(b, 7, 0);
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
        assertThat(lifecycle.expireDueOrders(BATCH)).isEqualTo(1);
        long d = order(line(b, 5));

        assertThat(status(c)).isEqualTo("CANCELADO");
        assertThat(status(d)).isEqualTo("PAGO_PENDIENTE");
        assertStock(b, 18, 5);
        assertThat(jdbc.queryForObject("SELECT reserved_until FROM orders WHERE id = ?", Timestamp.class, d)
                .toInstant()).isEqualTo(MutableClock.START.plus(RESERVATION).plus(RESERVATION));
    }
}
