package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.StockReconciliation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CLAUDE.md rule 5 on REAL persisted orders: every (from, to) pair of the 5x5 matrix. The order is placed through
 * the placement service and driven to {@code from} with legal operations only, then the transition to {@code to}
 * is attempted through the lifecycle service. The legal edge set is declared here, independently of
 * {@link OrderStatus#allowedTargets()}. A legal edge changes the status and moves stock as expected; an illegal one
 * throws and leaves the status AND the stock exactly as they were.
 *
 * <p>Scope of the PAGO_PENDIENTE column: NO service operation targets PAGO_PENDIENTE, so those 5 pairs check the
 * persisted status against the graph, not a service call. {@link #theServiceExposesNoOperationBackToPending()}
 * pins the exact public operation set of {@link OrderLifecycleService}, so a new path back to PAGO_PENDIENTE
 * fails the build and these 5 pairs must then be rewritten against that operation.
 */
class OrderTransitionMatrixIT extends AbstractPostgresIT {

    private static final int ON_HAND = 5;
    private static final int UNITS = 2;
    private static final Buyer BUYER =
            new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);

    private record Edge(OrderStatus from, OrderStatus to) {
    }

    /** The five legal edges, written out by hand on purpose (rule 5). */
    private static final Set<Edge> LEGAL = Set.of(
            new Edge(OrderStatus.PAGO_PENDIENTE, OrderStatus.PAGADO),
            new Edge(OrderStatus.PAGO_PENDIENTE, OrderStatus.CANCELADO),
            new Edge(OrderStatus.PAGADO, OrderStatus.DESPACHADO),
            new Edge(OrderStatus.PAGADO, OrderStatus.CANCELADO),
            new Edge(OrderStatus.DESPACHADO, OrderStatus.ENTREGADO));

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

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    static Stream<Arguments> allPairs() {
        return EnumSet.allOf(OrderStatus.class).stream().flatMap(from -> EnumSet.allOf(OrderStatus.class).stream()
                .map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairOnARealOrderIsLegalOnlyForTheFiveEdges(OrderStatus from, OrderStatus to) {
        long baseId = catalog.createBase(product("Base", "100.00")).id();
        inventory.createStock(baseId, ON_HAND);
        long id = placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), BUYER,
                List.of(new PlaceOrderCommand.Line(baseId, UNITS)))).orderId();
        driveTo(id, from);
        assertThat(status(id)).isEqualTo(from.name());
        Map<String, Object> before = stock(baseId);

        if (LEGAL.contains(new Edge(from, to))) {
            attempt(id, to);
            assertThat(status(id)).isEqualTo(to.name());
            assertThat(stock(baseId)).isEqualTo(expectedStockAfter(before, from, to));
        } else {
            assertThatThrownBy(() -> attempt(id, to)).isInstanceOf(IllegalOrderTransitionException.class);
            assertThat(status(id)).isEqualTo(from.name());
            assertThat(stock(baseId)).isEqualTo(before);
        }
    }

    @Test
    void theServiceExposesNoOperationBackToPending() {
        Set<String> publicOperations = Arrays.stream(OrderLifecycleService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(publicOperations)
                .containsExactlyInAnyOrder("markPaid", "dispatch", "deliver", "cancel", "expireDueOrders");
    }

    private void driveTo(long id, OrderStatus target) {
        switch (target) {
            case PAGO_PENDIENTE -> { }
            case PAGADO -> lifecycle.markPaid(id);
            case DESPACHADO -> {
                lifecycle.markPaid(id);
                lifecycle.dispatch(id);
            }
            case ENTREGADO -> {
                lifecycle.markPaid(id);
                lifecycle.dispatch(id);
                lifecycle.deliver(id);
            }
            case CANCELADO -> lifecycle.cancel(id, CancelReason.CANCELLED_BY_BUYER);
        }
    }

    /**
     * Attempts the move through the service operation for {@code to}. No operation leads back to PAGO_PENDIENTE
     * (pinned by {@link #theServiceExposesNoOperationBackToPending()}), so that column asks the persisted order's
     * status for the edge directly, under the order row lock: it checks the graph, not a service call.
     */
    private void attempt(long id, OrderStatus to) {
        switch (to) {
            case PAGADO -> lifecycle.markPaid(id);
            case DESPACHADO -> lifecycle.dispatch(id);
            case ENTREGADO -> lifecycle.deliver(id);
            case CANCELADO -> lifecycle.cancel(id, CancelReason.CANCELLED_BY_ADMIN);
            case PAGO_PENDIENTE -> new TransactionTemplate(txManager).executeWithoutResult(tx ->
                    orders.lockById(id).orElseThrow().status().requireTransitionTo(OrderStatus.PAGO_PENDIENTE));
        }
    }

    private static Map<String, Object> expectedStockAfter(Map<String, Object> before, OrderStatus from,
            OrderStatus to) {
        int onHand = (Integer) before.get("on_hand");
        int reserved = (Integer) before.get("reserved");
        if (from == OrderStatus.PAGO_PENDIENTE && to == OrderStatus.PAGADO) {
            return Map.of("on_hand", onHand - UNITS, "reserved", reserved - UNITS);
        }
        if (from == OrderStatus.PAGO_PENDIENTE && to == OrderStatus.CANCELADO) {
            return Map.of("on_hand", onHand, "reserved", reserved - UNITS);
        }
        if (from == OrderStatus.PAGADO && to == OrderStatus.CANCELADO) {
            return Map.of("on_hand", onHand + UNITS, "reserved", reserved);
        }
        return before;
    }

    private Map<String, Object> stock(long baseId) {
        return jdbc.queryForObject("SELECT on_hand, reserved FROM inventory_stock WHERE product_id = ?",
                (rs, row) -> Map.<String, Object>of("on_hand", rs.getInt("on_hand"),
                        "reserved", rs.getInt("reserved")), baseId);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }
}
