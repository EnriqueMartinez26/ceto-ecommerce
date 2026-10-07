package ar.cetobhb.orders.service;

import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderItem;
import ar.cetobhb.orders.repository.OrderRepository;
import java.time.Clock;
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
        inventory.commitSale(demandOf(order));
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
            inventory.release(demandOf(order));
        } else if (before == OrderStatus.PAGADO) {
            inventory.restock(demandOf(order));
        } else {
            // Invariant: Order.cancel only accepts PAGO_PENDIENTE and PAGADO, so this branch is unreachable.
            throw new IllegalStateException("Cancelled an order from unexpected status " + before);
        }
    }

    private Order lock(long orderId) {
        return orders.lockById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /** Base units of the order's line snapshots, aggregated per base product (ascending ids by construction). */
    private static BaseUnitDemand demandOf(Order order) {
        BaseUnitDemand demand = new BaseUnitDemand();
        for (OrderItem item : order.items()) {
            demand.addLine(item.baseProductId(), item.baseUnitsPerUnit(), item.quantity());
        }
        return demand;
    }
}
