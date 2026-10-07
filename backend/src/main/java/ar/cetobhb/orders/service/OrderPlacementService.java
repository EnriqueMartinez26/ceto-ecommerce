package ar.cetobhb.orders.service;

import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.config.OrderProperties;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places orders (CLAUDE.md rules 3, 6, 7). Prices and totals come from the catalog, never from the request.
 *
 * <p>Everything persistent happens in ONE transaction with a fixed lock order: the order row is inserted and
 * flushed first (the future idempotency claim, AD-7), then stock rows are locked in ascending base-product id by
 * {@link InventoryService#reserve}. Nothing reads a stock row before that lock query, so no stale instance can sit
 * in the persistence context. A business failure (unavailable product, shortage) is a runtime exception that rolls
 * back the order row too: nothing partial survives.
 *
 * <p>Seam for the idempotency slice: {@link #placeOrder} is the only place that opens the transaction, so a
 * same-key lookup and a replay in a second transaction wrap it without touching
 * {@link #placeInCurrentTransaction}.
 */
@Service
public class OrderPlacementService {

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

    /** Places the order in its own transaction. Validation, availability and shortage errors propagate as is. */
    public PlacedOrder placeOrder(PlaceOrderCommand command) {
        return transaction.execute(status -> placeInCurrentTransaction(command));
    }

    /**
     * Same work as {@link #placeOrder} inside the caller's transaction, which must already be open: the repository
     * calls would otherwise commit on their own and break the all-or-nothing contract. The caller owns the
     * rollback decision.
     */
    public PlacedOrder placeInCurrentTransaction(PlaceOrderCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("placeInCurrentTransaction requires an active transaction");
        }
        NormalizedOrderRequest request = normalizer.normalize(command);
        String fingerprint = OrderRequestFingerprint.of(request);
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
        inventory.reserve(demand);
        return new PlacedOrder(order.id(), order.orderNumber(), order.total(), order.reservedUntil());
    }
}
