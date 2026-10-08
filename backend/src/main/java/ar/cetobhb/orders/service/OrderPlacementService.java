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
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.NewOrder;
import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.NormalizedOrderRequest;
import ar.cetobhb.orders.domain.OrderLimits;
import ar.cetobhb.orders.domain.OrderNumber;
import ar.cetobhb.orders.domain.OrderRequestFingerprint;
import ar.cetobhb.orders.domain.OrderRequestNormalizer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Everything persistent happens in ONE transaction with a fixed lock order: the order row is inserted and
 * flushed first (the idempotency claim, AD-7), then stock rows are locked in ascending base-product id by
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
            return transaction.execute(status -> place(request, fingerprint));
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
     * Does no idempotency lookup: use {@link #placeOrder} for that.
     */
    public PlacedOrder placeInCurrentTransaction(PlaceOrderCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("placeInCurrentTransaction requires an active transaction");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("placeInCurrentTransaction cannot run in a read-only transaction");
        }
        NormalizedOrderRequest request = normalizer.normalize(command);
        return place(request, OrderRequestFingerprint.of(request));
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

        NewOrder data = new NewOrder(OrderNumber.format(orders.nextOrderNumber()), request.idempotencyKey(),
                fingerprint, request.name(), request.phoneE164(), request.address(), request.zone(),
                now.plus(reservationTime), lines);
        Order order = orders.saveAndFlush(Order.place(data, now));
        reserve(demand, products, productIds);
        return placed(order, false);
    }

    /**
     * A base without a stock row is an operator data problem (REQ-ERR-04): log it once (class and base product id,
     * never buyer data) and tell the buyer which REQUESTED products are unavailable: only those whose base is the
     * unconfigured one (the base itself or a combo over it), like the inactive-product failure. The exception's own
     * id is a base id the buyer may never have asked for.
     */
    private void reserve(BaseUnitDemand demand, Map<Long, ProductSnapshot> products, List<Long> requestedIds) {
        try {
            inventory.reserve(demand);
        } catch (StockNotConfiguredException e) {
            LOG.error("{} baseProductId={}", e.getClass().getSimpleName(), e.productId());
            Set<Long> affected = requestedIds.stream()
                    .filter(id -> products.get(id).baseProductId() == e.productId())
                    .collect(Collectors.toUnmodifiableSet());
            throw new ProductNotAvailableException(affected);
        }
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
