package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import java.sql.Timestamp;
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
 * Order lifecycle on a real Postgres with real commits (CLAUDE.md rules 3, 4, 5). Every scenario ends with the
 * stock reconciliation clean. The 25-pair graph walk lives in {@link OrderTransitionMatrixIT}.
 */
class OrderLifecycleIT extends AbstractPostgresIT {

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

    @Test
    void payingCommitsTheReservedUnitsForGood() {
        long b = base("B", 5);
        long id = order(line(b, 2));

        lifecycle.markPaid(id);

        assertThat(status(id)).isEqualTo("PAGADO");
        assertStock(b, 3, 0);
        assertThat(jdbc.queryForObject("SELECT paid_at FROM orders WHERE id = ?", Timestamp.class, id).toInstant())
                .isEqualTo(MutableClock.START);
    }

    @Test
    void payingAComboLineCommitsItsExpandedBaseUnits() {
        long b = base("B", 20);
        long combo = catalog.createCombo(product("Pack", "250.00"), b, 6).id();
        long id = order(line(combo, 2), line(b, 1));
        assertStock(b, 20, 13);

        lifecycle.markPaid(id);

        assertStock(b, 7, 0);
    }

    @Test
    void dispatchAndDeliverDoNotTouchStock() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        lifecycle.markPaid(id);

        lifecycle.dispatch(id);
        assertThat(status(id)).isEqualTo("DESPACHADO");
        assertStock(b, 3, 0);

        lifecycle.deliver(id);
        assertThat(status(id)).isEqualTo("ENTREGADO");
        assertStock(b, 3, 0);
    }

    @Test
    void cancellingAPendingOrderReleasesTheReservationForEitherReason() {
        long b = base("B", 5);
        long byBuyer = order(line(b, 2));
        long byAdmin = order(line(b, 1));
        assertStock(b, 5, 3);

        lifecycle.cancel(byBuyer, CancelReason.CANCELLED_BY_BUYER);
        assertStock(b, 5, 1);
        lifecycle.cancel(byAdmin, CancelReason.CANCELLED_BY_ADMIN);
        assertStock(b, 5, 0);

        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, byBuyer))
                .isEqualTo("CANCELLED_BY_BUYER");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, byAdmin))
                .isEqualTo("CANCELLED_BY_ADMIN");
    }

    @Test
    void cancellingAPaidOrderRestocksOnHandWithNoOtherSideEffect() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        lifecycle.markPaid(id);
        assertStock(b, 3, 0);

        lifecycle.cancel(id, CancelReason.CANCELLED_BY_ADMIN);

        assertThat(status(id)).isEqualTo("CANCELADO");
        assertStock(b, 5, 0);
        // Refund is agreed off-system (D-20261006-09): no refund table or type exists.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name ILIKE '%refund%'""", Integer.class)).isZero();
    }

    @Test
    void cancellingTwiceFailsWithoutReleasingOrRestockingAgain() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        long other = order(line(b, 1));
        lifecycle.cancel(id, CancelReason.CANCELLED_BY_BUYER);

        assertThatThrownBy(() -> lifecycle.cancel(id, CancelReason.CANCELLED_BY_BUYER))
                .isInstanceOf(IllegalOrderTransitionException.class);

        assertStock(b, 5, 1);
        assertThat(status(other)).isEqualTo("PAGO_PENDIENTE");
    }

    @Test
    void cancellingAPaidOrderTwiceDoesNotRestockTwice() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        lifecycle.markPaid(id);
        lifecycle.cancel(id, CancelReason.CANCELLED_BY_BUYER);

        assertThatThrownBy(() -> lifecycle.cancel(id, CancelReason.CANCELLED_BY_BUYER))
                .isInstanceOf(IllegalOrderTransitionException.class);

        assertStock(b, 5, 0);
    }

    @Test
    void dispatchedAndDeliveredOrdersCannotBeCancelled() {
        long b = base("B", 5);
        long dispatched = order(line(b, 1));
        long delivered = order(line(b, 1));
        lifecycle.markPaid(dispatched);
        lifecycle.dispatch(dispatched);
        lifecycle.markPaid(delivered);
        lifecycle.dispatch(delivered);
        lifecycle.deliver(delivered);

        assertThatThrownBy(() -> lifecycle.cancel(dispatched, CancelReason.CANCELLED_BY_ADMIN))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> lifecycle.cancel(delivered, CancelReason.CANCELLED_BY_ADMIN))
                .isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(status(dispatched)).isEqualTo("DESPACHADO");
        assertThat(status(delivered)).isEqualTo("ENTREGADO");
        assertStock(b, 3, 0);
    }

    @Test
    void expiredIsNotAValidManualReasonAndNothingChanges() {
        long b = base("B", 5);
        long paid = order(line(b, 2));
        long pending = order(line(b, 1));
        lifecycle.markPaid(paid);

        assertThatThrownBy(() -> lifecycle.cancel(paid, CancelReason.EXPIRED))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON);
        assertThatThrownBy(() -> lifecycle.cancel(pending, CancelReason.EXPIRED))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON);

        assertThat(status(paid)).isEqualTo("PAGADO");
        assertThat(status(pending)).isEqualTo("PAGO_PENDIENTE");
        assertStock(b, 3, 1);
    }

    @Test
    void aNullReasonIsAValidationErrorAndNothingChanges() {
        long b = base("B", 5);
        long id = order(line(b, 2));

        assertThatThrownBy(() -> lifecycle.cancel(id, null))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON);

        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertStock(b, 5, 2);
    }

    @Test
    void anUnknownOrderIdIsOrderNotFoundForEveryOperation() {
        assertThatThrownBy(() -> lifecycle.markPaid(9999)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.dispatch(9999)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.deliver(9999)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.cancel(9999, CancelReason.CANCELLED_BY_ADMIN))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void payingAnOrderCancelledByExpiryIsIllegalAndStockStaysPut() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        // Expiry is S11's job; until it exists the cancelled-by-EXPIRED row is a SQL fixture, like the real job's result.
        jdbc.update("""
                UPDATE orders SET status = 'CANCELADO', cancel_reason = 'EXPIRED', cancelled_at = ? WHERE id = ?""",
                Timestamp.from(MutableClock.START.plus(Duration.ofHours(49))), id);
        jdbc.update("UPDATE inventory_stock SET reserved = 0 WHERE product_id = ?", b);

        assertThatThrownBy(() -> lifecycle.markPaid(id)).isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(status(id)).isEqualTo("CANCELADO");
        assertStock(b, 5, 0);
    }

    @Test
    void payingAfterReservedUntilSucceedsWhileTheJobHasNotRun() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        Timestamp reservedUntil = jdbc.queryForObject("SELECT reserved_until FROM orders WHERE id = ?",
                Timestamp.class, id);
        clock.set(MutableClock.START.plus(Duration.ofHours(72)));

        lifecycle.markPaid(id);

        assertThat(status(id)).isEqualTo("PAGADO");
        assertStock(b, 3, 0);
        assertThat(jdbc.queryForObject("SELECT reserved_until FROM orders WHERE id = ?", Timestamp.class, id))
                .isEqualTo(reservedUntil);
    }

    @Test
    void reservedUntilNeverChangesAcrossTheLifecycle() {
        long b = base("B", 5);
        long id = order(line(b, 1));
        Timestamp reservedUntil = jdbc.queryForObject("SELECT reserved_until FROM orders WHERE id = ?",
                Timestamp.class, id);
        clock.set(MutableClock.START.plus(Duration.ofHours(1)));

        lifecycle.markPaid(id);
        lifecycle.dispatch(id);
        lifecycle.deliver(id);

        assertThat(jdbc.queryForObject("SELECT reserved_until FROM orders WHERE id = ?", Timestamp.class, id))
                .isEqualTo(reservedUntil);
    }

    @Test
    void mixedSequenceLeavesReservedEqualToThePendingOrderOnly() {
        long b = base("B", 20);
        long a = order(line(b, 2));
        long second = order(line(b, 3));
        lifecycle.markPaid(a);
        lifecycle.cancel(second, CancelReason.CANCELLED_BY_BUYER);
        long d = order(line(b, 4));

        assertThat(status(d)).isEqualTo("PAGO_PENDIENTE");
        assertStock(b, 18, 4);
    }

    @Test
    void concurrentPayAndCancelOfOneOrderNeverDeadlockAndEndConsistent() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> {
                lifecycle.markPaid(id);
                return "pay";
            });
            tasks.add(() -> {
                lifecycle.cancel(id, CancelReason.CANCELLED_BY_BUYER);
                return "cancel";
            });
        }

        List<Outcome<String>> outcomes = BurstRunner.run(tasks);

        // Pay then cancel are both legal edges in sequence, so the cancel always wins eventually; the pay wins only
        // when it ran first. Every loser failed with ILLEGAL_TRANSITION, nothing else.
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::error))
                .allMatch(e -> e instanceof IllegalOrderTransitionException);
        assertThat(outcomes.stream().filter(o -> o.succeeded() && "cancel".equals(o.value()))).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.succeeded() && "pay".equals(o.value())).count())
                .isLessThanOrEqualTo(1);
        assertThat(status(id)).isEqualTo("CANCELADO");
        assertStock(b, 5, 0);
    }

    @Test
    void concurrentPaymentsOfOneOrderCommitTheStockExactlyOnce() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                lifecycle.markPaid(id);
                return true;
            });
        }

        List<Outcome<Boolean>> outcomes = BurstRunner.run(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::error))
                .hasSize(9).allMatch(e -> e instanceof IllegalOrderTransitionException);
        assertStock(b, 3, 0);
    }

    @Test
    void concurrentCancellationsOfOneOrderReleaseTheStockExactlyOnce() {
        long b = base("B", 5);
        long id = order(line(b, 2));
        long other = order(line(b, 1));
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                lifecycle.cancel(id, CancelReason.CANCELLED_BY_ADMIN);
                return true;
            });
        }

        List<Outcome<Boolean>> outcomes = BurstRunner.run(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::error))
                .hasSize(9).allMatch(e -> e instanceof IllegalOrderTransitionException);
        assertStock(b, 5, 1);
        assertThat(status(other)).isEqualTo("PAGO_PENDIENTE");
    }
}
