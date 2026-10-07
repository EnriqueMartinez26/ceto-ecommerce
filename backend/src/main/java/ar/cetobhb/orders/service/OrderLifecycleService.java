package ar.cetobhb.orders.service;

import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order lifecycle operations with their stock effects (CLAUDE.md rules 3, 4, 5). Each operation is ONE transaction
 * with the global lock order: the order row first ({@link OrderRepository#lockById}, which must be the first read of
 * that row in the transaction), then the stock rows in ascending base-product id (inside {@link InventoryService}).
 * Concurrent operations on one order therefore serialize on its row, and the loser sees the committed status and
 * fails with an illegal transition instead of touching stock twice.
 *
 * <p>The domain transition runs BEFORE the stock effect: an illegal edge or a bad reason throws before any stock row
 * is locked, and any failure rolls the whole transaction back. Refunds are agreed off-system (D-20261006-09): paying
 * commits the sale, cancelling a paid order only returns the units to {@code on_hand}. {@code reserved_until} is
 * never touched here; paying after it has passed is allowed while the expiry job has not cancelled the order
 * (D-20261007-04).
 */
@Service
public class OrderLifecycleService {

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final Clock clock;

    public OrderLifecycleService(OrderRepository orders, InventoryService inventory, Clock clock) {
        this.orders = orders;
        this.inventory = inventory;
        this.clock = clock;
    }

    /** PAGO_PENDIENTE to PAGADO: reserved units leave the shelf for good ({@code on_hand} and {@code reserved}). */
    @Transactional
    public void markPaid(long orderId) {
        Order order = lock(orderId);
        order.markPaid(clock.instant());
        inventory.commitSale(OrderStockDemand.of(order));
    }

    @Transactional
    public void dispatch(long orderId) {
        lock(orderId).dispatch(clock.instant());
    }

    @Transactional
    public void deliver(long orderId) {
        lock(orderId).deliver(clock.instant());
    }

    /**
     * Manual cancellation (buyer or admin; EXPIRED is the job's reason only). From PAGO_PENDIENTE the reservation is
     * released; from PAGADO the units go back to {@code on_hand}.
     */
    @Transactional
    public void cancel(long orderId, CancelReason reason) {
        Order order = lock(orderId);
        OrderStatus before = order.status();
        order.cancel(reason, clock.instant());
        if (before == OrderStatus.PAGO_PENDIENTE) {
            inventory.release(OrderStockDemand.of(order));
        } else if (before == OrderStatus.PAGADO) {
            inventory.restock(OrderStockDemand.of(order));
        } else {
            // Invariant: Order.cancel only accepts PAGO_PENDIENTE and PAGADO, so this branch is unreachable.
            throw new IllegalStateException("Cancelled an order from unexpected status " + before);
        }
    }

    /**
     * Expires up to {@code batchSize} due reservations (REQ-LIF-06, AD-11) and returns how many were expired.
     *
     * <p>ONE transaction per batch. Lock order is the global one (rule 3): the order rows first, taken only through
     * {@link OrderRepository#lockExpiredBatch} (oldest deadline first, {@code FOR UPDATE SKIP LOCKED}, so an order a
     * payment or a concurrent run holds is skipped, not waited on) as the first read of those rows; then the stock
     * rows, ascending by base product id across the WHOLE batch: the demand of every order is merged into a single
     * {@link BaseUnitDemand} and released with one call, so no order-by-order interleaving can lock stock rows in
     * another sequence than a concurrent placement or payment does.
     *
     * <p>{@link Order#expire} rechecks status and {@code reserved_until} under the lock, and the release follows the
     * item snapshots, exactly like a manual cancel of a pending order. A failure (a broken stock invariant) is NOT
     * caught: the whole batch rolls back, nothing is half-released, and the job logs it loudly and retries on the
     * next tick (AD-11). Never catch an exception here and carry on in the same transaction: the stock service
     * marks it rollback-only.
     */
    @Transactional
    public int expireDueOrders(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        Instant now = clock.instant();
        List<Order> due = orders.lockExpiredBatch(now, batchSize);
        BaseUnitDemand demand = new BaseUnitDemand();
        for (Order order : due) {
            order.expire(now);
            OrderStockDemand.add(demand, order);
        }
        if (!demand.isEmpty()) {
            inventory.release(demand);
        }
        return due.size();
    }

    private Order lock(long orderId) {
        return orders.lockById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
