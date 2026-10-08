package ar.cetobhb.orders.service;

import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import ar.cetobhb.common.persistence.ConstraintViolations;
import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.domain.StockNotConfiguredException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.config.OrderProperties;
import ar.cetobhb.orders.domain.CheckoutInProgressException;
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.NewOrder;
import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.NormalizedOrderRequest;
import ar.cetobhb.orders.domain.OrderLimits;
import ar.cetobhb.orders.domain.OrderNumber;
import ar.cetobhb.orders.domain.OrderRequestFingerprint;
import ar.cetobhb.orders.domain.OrderRequestNormalizer;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.TooManyPendingOrdersException;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places orders (CLAUDE.md rules 3, 6, 7, 10). Prices and totals come from the catalog, never from the request.
 *
 * <p>Everything persistent happens in ONE transaction with a fixed lock order: the advisory lock of the buyer phone
 * (it serializes the pending-orders cap, REQ-ABU-01), then the order row is inserted and flushed first (the
 * idempotency claim, AD-7), then stock rows are locked in ascending base-product id by
 * {@link InventoryService#reserve}. Nothing reads a stock row before that lock query, so no stale instance can sit
 * in the persistence context. A business failure (unavailable product, shortage) is a runtime exception that rolls
 * back the order row too: nothing partial survives.
 *
 * <p>{@link #placeOrder} is the only place that opens transactions: it wraps this work with the idempotency
 * lookup and the replay after a lost race.
 */
@Service
public class OrderPlacementService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderPlacementService.class);
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "orders_idempotency_key_uk";

    private final OrderRequestNormalizer normalizer;
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final OrderRepository orders;
    private final Clock clock;
    private final Duration reservationTime;
    private final int maxPendingPerPhone;
    private final TransactionTemplate transaction;

    public OrderPlacementService(OrderProperties properties, CatalogService catalog, InventoryService inventory,
            OrderRepository orders, Clock clock, PlatformTransactionManager transactionManager) {
        this.normalizer = new OrderRequestNormalizer(new PhoneNumberNormalizer(),
                new OrderLimits(properties.maxUnitsPerLine(), properties.maxLinesPerOrder()));
        this.catalog = catalog;
        this.inventory = inventory;
        this.orders = orders;
        this.clock = clock;
        this.reservationTime = Duration.ofHours(properties.reservationHours());
        this.maxPendingPerPhone = properties.maxPendingPerPhone();
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Places the order, idempotently by key (CLAUDE.md rule 10, D-20261006-11, D-20261007-04).
     *
     * <p>The key lookup runs FIRST, in its own short transaction and before the catalog or stock are touched, so a
     * retry of an order that already exists is replayed even if the product was deactivated or the stock is gone
     * since. A hit with the same fingerprint is a replay (whatever the order status), with another one an
     * {@link IdempotencyConflictException}. On a miss the order is created in one transaction; if a concurrent
     * request with the same key committed in between, the unique constraint fires when the order row is inserted
     * (before any stock is reserved, AD-7), that transaction rolls back and the lookup is repeated in a NEW
     * transaction. Any other failure propagates as is. A failed placement leaves no order row, so it never burns
     * the key.
     *
     * <p>Must not be called inside an active transaction (an {@link IllegalStateException} is thrown): the replay
     * after a lost race needs a transaction of its own. If that replay finds no winner row, the unique constraint
     * and the never-deleted orders contradict each other: that invariant violation is an
     * {@link IllegalStateException} too, with the original constraint violation as its cause.
     *
     * <p>Order numbers come from a database sequence consumed BEFORE the insert and never given back, so a failed or
     * losing placement leaves a gap: numbers are readable and increasing, not gapless (CLAUDE.md rule 9).
     */
    public PlacedOrder placeOrder(PlaceOrderCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("placeOrder opens its own transactions and cannot run inside an active"
                    + " one: the lost-race replay needs a fresh transaction (use placeInCurrentTransaction)");
        }
        NormalizedOrderRequest request = normalizer.normalize(command);
        String fingerprint = OrderRequestFingerprint.of(request);
        PlacedOrder existing = transaction.execute(status -> replayOrNull(request, fingerprint));
        if (existing != null) {
            return existing;
        }
        try {
            return transaction.execute(status -> placeUnderPhoneLock(request, fingerprint));
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolations.isConstraint(e, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw e;
            }
            PlacedOrder winner = transaction.execute(status -> replayOrNull(request, fingerprint));
            if (winner == null) {
                // Invariant violation: the unique constraint proves a committed row holds this key and orders are
                // never deleted, so the replay lookup must find it.
                throw new IllegalStateException("idempotency invariant violated: the unique constraint fired for key "
                        + request.idempotencyKey() + " but no order holds it", e);
            }
            return winner;
        }
    }

    /** The existing order of this key as a replay, null when the key is free; conflict when the request differs. */
    private PlacedOrder replayOrNull(NormalizedOrderRequest request, String fingerprint) {
        return orders.findByIdempotencyKey(request.idempotencyKey()).map(order -> {
            if (!order.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(order.id());
            }
            return placed(order, true);
        }).orElse(null);
    }

    /**
     * Creates the order inside the caller's transaction, which must already be open: the repository calls would
     * otherwise commit on their own and break the all-or-nothing contract. The caller owns the rollback decision.
     * A read-only transaction is rejected as well: placement writes.
     *
     * <p>Domain exceptions (unavailable product, shortage) leave the transactional collaborators and mark the
     * caller's transaction rollback-only. Callers must not catch one and carry on in the same transaction: the commit
     * would fail with an {@code UnexpectedRollbackException}.
     *
     * <p>Precondition: call it BEFORE touching orders or stock in the transaction. It takes the per-phone advisory
     * lock first, so any earlier order or stock lock in the same transaction inverts the lock order
     * (advisory(phone) -> order row -> stock rows): a concurrent {@link #placeOrder} of that phone may then wait for
     * rows this transaction holds, until the {@code lock_timeout} (the advisory lock itself never waits, so it cannot
     * close a deadlock cycle). A phone whose lock another checkout holds gets {@link CheckoutInProgressException}.
     *
     * <p>Contract differences from {@link #placeOrder}: it does NO idempotency lookup and no replay, so a duplicate
     * key surfaces as a {@link DataIntegrityViolationException} (constraint {@code orders_idempotency_key_uk}) in the
     * caller's transaction, which the caller must roll back. The pending cap is checked BEFORE the key, so a retry
     * at the cap through this method gets {@link TooManyPendingOrdersException} (429), unlike {@code placeOrder},
     * which replays it as a 200.
     */
    public PlacedOrder placeInCurrentTransaction(PlaceOrderCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("placeInCurrentTransaction requires an active transaction");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("placeInCurrentTransaction cannot run in a read-only transaction");
        }
        NormalizedOrderRequest request = normalizer.normalize(command);
        requirePhoneLock(request);
        requirePendingCapacity(request);
        return place(request, OrderRequestFingerprint.of(request));
    }

    /**
     * Takes the per-phone lock WITHOUT waiting (D-20261008-01): if another checkout of the same phone holds it, this
     * one is rejected with {@link CheckoutInProgressException} (503, {@code Retry-After: 1}). A waiting lock would pin
     * one pooled connection per concurrent request of the phone.
     */
    private void requirePhoneLock(NormalizedOrderRequest request) {
        if (!orders.tryLockBuyerPhone(request.phoneE164())) {
            throw new CheckoutInProgressException();
        }
    }

    /**
     * Create path of {@link #placeOrder} (REQ-ABU-01): the per-phone advisory lock FIRST (try-lock, never waits:
     * {@link CheckoutInProgressException} when another checkout of the phone holds it), then the idempotency lookup
     * AGAIN, then the cap. Taking the lock before counting serializes the count-and-insert of one phone. The re-check
     * under the lock keeps a retry of an order that was committed between the first lookup and the lock a 200
     * replay: counted first it would meet its own order and be rejected. A replay found here writes nothing, reserves
     * nothing and consumes no order number.
     */
    private PlacedOrder placeUnderPhoneLock(NormalizedOrderRequest request, String fingerprint) {
        requirePhoneLock(request);
        PlacedOrder replay = replayOrNull(request, fingerprint);
        if (replay != null) {
            return replay;
        }
        requirePendingCapacity(request);
        return place(request, fingerprint);
    }

    /** Must run after the phone lock, in the same transaction, and before any order number or stock is taken. */
    private void requirePendingCapacity(NormalizedOrderRequest request) {
        long pending = orders.countByBuyerPhoneE164AndStatus(request.phoneE164(), OrderStatus.PAGO_PENDIENTE);
        if (pending >= maxPendingPerPhone) {
            throw new TooManyPendingOrdersException();
        }
    }

    private PlacedOrder place(NormalizedOrderRequest request, String fingerprint) {
        Instant now = clock.instant();

        List<Long> productIds = request.lines().stream().map(PlaceOrderCommand.Line::productId).toList();
        Map<Long, ProductSnapshot> products = catalog.findForOrder(productIds);

        List<NewOrderLine> lines = new ArrayList<>();
        BaseUnitDemand demand = new BaseUnitDemand();
        for (PlaceOrderCommand.Line requested : request.lines()) {
            ProductSnapshot product = products.get(requested.productId());
            lines.add(new NewOrderLine(product.id(), product.name(), product.isCombo(), product.price(),
                    requested.quantity(), product.baseProductId(), product.baseUnitsPerUnit()));
            demand.addLine(product.baseProductId(), product.baseUnitsPerUnit(), requested.quantity());
        }

        requireStockRows(demand, products, productIds);

        NewOrder data = new NewOrder(OrderNumber.format(orders.nextOrderNumber()), request.idempotencyKey(),
                fingerprint, request.name(), request.phoneE164(), request.address(), request.zone(),
                now.plus(reservationTime), lines);
        Order order = orders.saveAndFlush(Order.place(data, now));
        reserve(demand, products, productIds);
        return placed(order, false);
    }

    /**
     * A base without a stock row is an operator data problem (REQ-ERR-04): log it once (class and base product ids,
     * never buyer data) and tell the buyer which REQUESTED products are unavailable: only those whose base is an
     * unconfigured one (the base itself or a combo over it), like the inactive-product failure.
     *
     * <p>Checked BEFORE the order row and the order number, with a non-locking projection query (never an entity,
     * so the later lock query still reads the database) over ALL bases, so every affected id is reported, not just
     * the first one the lock loop would hit.
     */
    private void requireStockRows(BaseUnitDemand demand, Map<Long, ProductSnapshot> products,
            List<Long> requestedIds) {
        Set<Long> configured = inventory.availableByBase(demand.productIds()).keySet();
        Set<Long> missing = demand.productIds().stream().filter(id -> !configured.contains(id))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!missing.isEmpty()) {
            throw unavailable(missing, products, requestedIds);
        }
    }

    /**
     * The reserve lock loop stays as the safety net for the race where a stock row disappears between the check
     * above and the lock.
     */
    private void reserve(BaseUnitDemand demand, Map<Long, ProductSnapshot> products, List<Long> requestedIds) {
        try {
            inventory.reserve(demand);
        } catch (StockNotConfiguredException e) {
            throw unavailable(Set.of(e.productId()), products, requestedIds);
        }
    }

    private static ProductNotAvailableException unavailable(Set<Long> missingBaseIds,
            Map<Long, ProductSnapshot> products, List<Long> requestedIds) {
        LOG.error("{} baseProductIds={}", StockNotConfiguredException.class.getSimpleName(), missingBaseIds);
        Set<Long> affected = requestedIds.stream()
                .filter(id -> missingBaseIds.contains(products.get(id).baseProductId()))
                .collect(Collectors.toUnmodifiableSet());
        return new ProductNotAvailableException(affected);
    }

    /** Built inside the transaction that loaded the order: the lines are lazy and read from the snapshot rows. */
    private static PlacedOrder placed(Order order, boolean replayed) {
        List<PlacedOrder.PlacedLine> lines = order.items().stream()
                .map(item -> new PlacedOrder.PlacedLine(item.productId(), item.productName(), item.quantity(),
                        item.unitPrice(), item.lineTotal()))
                .toList();
        return new PlacedOrder(order.id(), order.orderNumber(), order.status(), order.shippingZone(), order.total(),
                order.reservedUntil(), replayed, lines);
    }
}
