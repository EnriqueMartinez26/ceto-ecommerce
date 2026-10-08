package ar.cetobhb.orders.service;

import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    private static final Logger LOG = LoggerFactory.getLogger(OrderLifecycleService.class);
    /** Postgres SQLState of lock_not_available (a lock_timeout expiring). */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OrderLifecycleService(OrderRepository orders, InventoryService inventory, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.inventory = inventory;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
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
     * Expires up to {@code batchSize} due reservations (REQ-LIF-06, AD-11) and reports which orders were expired
     * and which failed.
     *
     * <p>Fast path, ONE transaction per batch. Lock order is the global one (rule 3): the order rows first, taken
     * only through {@link OrderRepository#lockExpiredBatch} (oldest deadline first, {@code FOR UPDATE SKIP LOCKED},
     * so an order a payment or a concurrent run holds is skipped, not waited on) as the first read of those rows;
     * then the stock rows, ascending by base product id across the WHOLE batch: the demand of every order is merged
     * into a single {@link BaseUnitDemand} and released with one call, so no order-by-order interleaving can lock
     * stock rows in another sequence than a concurrent placement or payment does.
     *
     * <p>Poison-batch fallback (D-20261007-12). If the batch transaction fails with a {@link RuntimeException} (a
     * broken stock invariant such as a missing stock row or {@code reserved} below the demand) it rolls back
     * entirely, nothing is half-released. One corrupt order must not stop every younger order from ever expiring, so
     * the method then reads the candidate ids WITHOUT locks ({@link OrderRepository#findDueIds}, same filter and
     * ordering, same limit) and expires each order in its OWN transaction: {@code lockById} as the first read of
     * the row, {@link Order#expire} rechecks status and deadline under the lock (an order paid or cancelled in the
     * meantime is skipped, not a failure), then that order's snapshot demand is released, ascending by base product
     * id. The global lock order holds in both paths. An order that still fails is logged at ERROR with its id, the
     * exception class and the SQLState when the cause chain has one (no PII), collected in {@link ExpiryResult#failedOrderIds()} and left PAGO_PENDIENTE until it is repaired by
     * hand; the others advance. The fallback stops at the FIRST lock timeout (lock_timeout, SQLState 55P03): a lock
     * held that long would make every following order wait the full timeout too; the orders not tried stay pending
     * for the next tick. A failed order keeps occupying a slot of the oldest-due window, so as many corrupt
     * orders as the batch size would starve the younger ones: that is what the ERROR log is for.
     *
     * <p>This method is deliberately NOT {@code @Transactional}: the batch and each order run in their own
     * transactions through a {@link TransactionTemplate}, so it rejects a caller with an active transaction (it would
     * join it, and a rolled-back batch would poison the caller's transaction instead of falling back). Never catch an exception inside one of them and carry on
     * in the same transaction: the stock service marks it rollback-only.
     */
    public ExpiryResult expireDueOrders(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("expireDueOrders opens its own transactions and cannot run inside an"
                    + " active one: the per-order fallback needs independent transactions");
        }
        Instant now = clock.instant();
        try {
            List<Long> expired = transactions.execute(status -> expireBatch(now, batchSize));
            return new ExpiryResult(expired, List.of());
        } catch (RuntimeException batchFailure) {
            LOG.warn("Expiry batch rolled back ({}); retrying order by order", batchFailure.getClass().getSimpleName());
            return expireOneByOne(now, batchSize);
        }
    }

    private List<Long> expireBatch(Instant now, int batchSize) {
        List<Order> due = orders.lockExpiredBatch(now, batchSize);
        BaseUnitDemand demand = new BaseUnitDemand();
        for (Order order : due) {
            order.expire(now);
            OrderStockDemand.add(demand, order);
        }
        if (!demand.isEmpty()) {
            inventory.release(demand);
        }
        return due.stream().map(Order::id).toList();
    }

    private ExpiryResult expireOneByOne(Instant now, int batchSize) {
        List<Long> expired = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (long id : orders.findDueIds(now, batchSize)) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> expireOne(id, now)))) {
                    expired.add(id);
                }
            } catch (RuntimeException failure) {
                // Id, exception class and SQLState only: exception messages may carry business data.
                SQLException sql = sqlCause(failure);
                if (sql == null) {
                    LOG.error("Could not expire order {}: {}", id, failure.getClass().getSimpleName());
                } else {
                    LOG.error("Could not expire order {}: {} (SQLState {})", id, failure.getClass().getSimpleName(),
                            sql.getSQLState());
                }
                failed.add(id);
                if (isLockTimeout(failure, sql)) {
                    // A lock is held for longer than the wait: the orders behind it would each wait the full
                    // lock_timeout too and the tick would run for minutes. The next tick retries them.
                    LOG.warn("Expiry fallback stopped at the first lock timeout; the remaining orders wait for the"
                            + " next tick");
                    break;
                }
            }
        }
        return new ExpiryResult(expired, failed);
    }

    private static boolean isLockTimeout(RuntimeException failure, SQLException sql) {
        return failure instanceof PessimisticLockingFailureException
                || failure instanceof jakarta.persistence.LockTimeoutException
                || (sql != null && LOCK_NOT_AVAILABLE.equals(sql.getSQLState()));
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /** One order in its own transaction; false when it is no longer due (paid, cancelled or gone meanwhile). */
    private boolean expireOne(long id, Instant now) {
        Order order = orders.lockById(id).orElse(null);
        if (order == null || order.status() != OrderStatus.PAGO_PENDIENTE || now.isBefore(order.reservedUntil())) {
            return false;
        }
        order.expire(now);
        inventory.release(OrderStockDemand.of(order));
        return true;
    }

    private Order lock(long orderId) {
        return orders.lockById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
