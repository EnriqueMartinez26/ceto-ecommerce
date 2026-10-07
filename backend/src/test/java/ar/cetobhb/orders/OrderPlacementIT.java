package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
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
 * Order placement on a real Postgres with real commits (no @Transactional on the test). Every scenario ends with
 * the stock reconciliation clean (CLAUDE.md rule 4). Idempotent replay and bursts belong to the next slice.
 */
class OrderPlacementIT extends AbstractPostgresIT {

    private static final String PHONE = "+5493515550000";
    private static final Buyer BUYER =
            new Buyer("Maria Perez", PHONE, "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);

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

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long base(String name, String price, int onHand) {
        long id = catalog.createBase(product(name, price)).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private long combo(String name, String price, long baseId, int units) {
        return catalog.createCombo(product(name, price), baseId, units).id();
    }

    private static PlaceOrderCommand command(PlaceOrderCommand.Line... lines) {
        return new PlaceOrderCommand(UUID.randomUUID().toString(), BUYER, List.of(lines));
    }

    private static PlaceOrderCommand.Line line(long productId, int quantity) {
        return new PlaceOrderCommand.Line(productId, quantity);
    }

    private int reserved(long productId) {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class,
                productId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertNothingPersisted() {
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(reserved), 0) FROM inventory_stock", Long.class)).isZero();
    }

    @Test
    void aValidOrderIsPendingReservesStockAndExpiresFortyEightHoursLater() {
        long b = base("Base", "100.00", 5);

        PlacedOrder placed = placement.placeOrder(command(line(b, 2)));

        assertThat(placed.orderNumber()).isEqualTo("CETO-000001");
        assertThat(placed.total()).isEqualByComparingTo("200.00");
        assertThat(placed.reservedUntil()).isEqualTo(MutableClock.START.plus(Duration.ofHours(48)));
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", placed.orderId());
        assertThat(order).containsEntry("status", "PAGO_PENDIENTE").containsEntry("order_number", "CETO-000001");
        assertThat((BigDecimal) order.get("total")).isEqualByComparingTo("200.00");
        assertThat(reserved(b)).isEqualTo(2);
    }

    @Test
    void reservedUntilFollowsTheInjectedClockAndOrderNumbersAreSequential() {
        long b = base("Base", "10.00", 5);
        Instant later = Instant.parse("2026-07-01T08:30:00Z");
        clock.set(later);

        placement.placeOrder(command(line(b, 1)));
        PlacedOrder second = placement.placeOrder(command(line(b, 1)));

        assertThat(second.orderNumber()).isEqualTo("CETO-000002");
        assertThat(second.reservedUntil()).isEqualTo(later.plus(Duration.ofHours(48)));
        assertThat(jdbc.queryForObject("SELECT created_at FROM orders WHERE id = ?", Timestamp.class,
                second.orderId()).toInstant()).isEqualTo(later);
        assertThat(reserved(b)).isEqualTo(2);
    }

    @Test
    void lineSnapshotsAndTotalsComeFromDatabasePrices() {
        long a = base("Alfajor", "33.33", 10);
        long b = base("Budin", "12.50", 10);

        PlacedOrder placed = placement.placeOrder(command(line(b, 2), line(a, 3)));

        assertThat(placed.total()).isEqualByComparingTo("124.99");
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT product_name, unit_price, quantity, line_total FROM order_items "
                        + "WHERE order_id = ? ORDER BY line_no", placed.orderId());
        assertThat(items).extracting(i -> i.get("product_name")).containsExactly("Alfajor", "Budin");
        assertThat((BigDecimal) items.get(0).get("line_total")).isEqualByComparingTo("99.99");
        assertThat((BigDecimal) items.get(1).get("unit_price")).isEqualByComparingTo("12.50");
    }

    @Test
    void eachOrderSnapshotsThePriceReadAtItsOwnPlacementTime() {
        long b = base("Base", "100.00", 10);
        PlacedOrder first = placement.placeOrder(command(line(b, 2)));

        // No price-update API exists yet, so the change goes straight to SQL.
        jdbc.update("UPDATE products SET price = 150.00 WHERE id = ?", b);
        PlacedOrder second = placement.placeOrder(command(line(b, 2)));

        assertThat(unitPrice(first.orderId())).isEqualByComparingTo("100.00");
        assertThat(total(first.orderId())).isEqualByComparingTo("200.00");
        assertThat(unitPrice(second.orderId())).isEqualByComparingTo("150.00");
        assertThat(total(second.orderId())).isEqualByComparingTo("300.00");
    }

    private BigDecimal unitPrice(long orderId) {
        return jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id = ?", BigDecimal.class, orderId);
    }

    private BigDecimal total(long orderId) {
        return jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, orderId);
    }

    @Test
    void aComboReservesItsBaseUnits() {
        long b = base("Base", "100.00", 20);
        long c = combo("Combo x3", "270.00", b, 3);

        placement.placeOrder(command(line(c, 1)));

        assertThat(reserved(b)).isEqualTo(3);
    }

    @Test
    void baseAndComboLinesAggregateIntoOneReservationOfEight() {
        long b = base("Base", "100.00", 20);
        long c = combo("Combo x3", "270.00", b, 3);

        PlacedOrder placed = placement.placeOrder(command(line(b, 2), line(c, 2)));

        assertThat(reserved(b)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT SUM(base_units_total) FROM order_items WHERE order_id = ?",
                Long.class, placed.orderId())).isEqualTo(8L);
    }

    @Test
    void aDeactivatedProductIsRejectedAndNothingIsReserved() {
        long b = base("Base", "100.00", 5);
        long gone = base("Gone", "50.00", 5);
        catalog.deactivate(gone);

        assertThatThrownBy(() -> placement.placeOrder(command(line(b, 1), line(gone, 1))))
                .isInstanceOfSatisfying(ProductNotAvailableException.class,
                        e -> assertThat(e.productIds()).containsExactly(gone));

        assertNothingPersisted();
    }

    @Test
    void aComboOverADeactivatedBaseIsRejectedAndNothingIsPersisted() {
        long b = base("Base", "100.00", 20);
        long c = combo("Combo x3", "270.00", b, 3);
        catalog.deactivate(b);

        assertThatThrownBy(() -> placement.placeOrder(command(line(c, 1))))
                .isInstanceOfSatisfying(ProductNotAvailableException.class,
                        e -> assertThat(e.productIds()).containsExactly(c));

        assertNothingPersisted();
    }

    @Test
    void aMissingProductIsRejectedAndNothingIsPersisted() {
        long b = base("Base", "100.00", 5);

        assertThatThrownBy(() -> placement.placeOrder(command(line(b, 1), line(9999, 1))))
                .isInstanceOf(ProductNotAvailableException.class);

        assertNothingPersisted();
    }

    @Test
    void deactivatingAProductAfterTheOrderLeavesOrderLinesAndReservationUntouched() {
        long b = base("Base", "100.00", 5);
        PlacedOrder placed = placement.placeOrder(command(line(b, 2)));

        catalog.deactivate(b);

        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(1);
        assertThat(reserved(b)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, placed.orderId()))
                .isEqualByComparingTo("200.00");
    }

    @Test
    void insufficientStockOnOneProductRejectsTheWholeCartAndPersistsNothing() {
        long a = base("A", "10.00", 5);
        long b = base("B", "10.00", 1);

        assertThatThrownBy(() -> placement.placeOrder(command(line(a, 2), line(b, 2))))
                .isInstanceOfSatisfying(InsufficientStockException.class,
                        e -> assertThat(e.productIds()).containsExactly(b));

        assertThat(reserved(a)).isZero();
        assertNothingPersisted();
    }

    @Test
    void aComboNeedingMoreBaseUnitsThanAvailableFailsWhileOneUnitSucceeds() {
        long b = base("Base", "100.00", 5);
        long c = combo("Combo x3", "270.00", b, 3);

        assertThatThrownBy(() -> placement.placeOrder(command(line(c, 2))))
                .isInstanceOf(InsufficientStockException.class);
        assertNothingPersisted();

        placement.placeOrder(command(line(c, 1)));
        assertThat(reserved(b)).isEqualTo(3);
    }

    private static PlaceOrderCommand withBuyer(Buyer buyer, long productId) {
        return new PlaceOrderCommand(UUID.randomUUID().toString(), buyer, List.of(line(productId, 1)));
    }

    private static PlaceOrderCommand withLines(List<PlaceOrderCommand.Line> lines) {
        return new PlaceOrderCommand(UUID.randomUUID().toString(), BUYER, lines);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                scenario("quantity 0", ReasonCode.QUANTITY_BELOW_MIN, id -> withLines(List.of(line(id, 0)))),
                scenario("quantity -1", ReasonCode.QUANTITY_BELOW_MIN, id -> withLines(List.of(line(id, -1)))),
                scenario("quantity 11", ReasonCode.QUANTITY_ABOVE_MAX, id -> withLines(List.of(line(id, 11)))),
                scenario("empty cart", ReasonCode.EMPTY_CART, id -> withLines(List.of())),
                scenario("duplicate line", ReasonCode.DUPLICATE_PRODUCT_LINE,
                        id -> withLines(List.of(line(id, 1), line(id, 2)))),
                scenario("21 lines", ReasonCode.TOO_MANY_LINES, id -> {
                    List<PlaceOrderCommand.Line> lines = new ArrayList<>();
                    for (int i = 0; i < 21; i++) {
                        lines.add(line(id + i, 1));
                    }
                    return withLines(lines);
                }),
                scenario("bad phone", ReasonCode.INVALID_PHONE, id -> withBuyer(
                        new Buyer("Maria Perez", "12345", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL), id)),
                scenario("blank name", ReasonCode.BLANK_BUYER_NAME, id -> withBuyer(
                        new Buyer("  ", PHONE, "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL), id)),
                scenario("blank address", ReasonCode.BLANK_ADDRESS, id -> withBuyer(
                        new Buyer("Maria Perez", PHONE, " ", ShippingZone.CORDOBA_CAPITAL), id)),
                scenario("missing zone", ReasonCode.INVALID_ZONE, id -> withBuyer(
                        new Buyer("Maria Perez", PHONE, "Calle Falsa 123", null), id)),
                scenario("missing key", ReasonCode.MISSING_IDEMPOTENCY_KEY,
                        id -> new PlaceOrderCommand(null, BUYER, List.of(line(id, 1)))));
    }

    private static Arguments scenario(String name, ReasonCode expected, LongFunction<PlaceOrderCommand> request) {
        return Arguments.of(name, expected, request);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void validationFailuresSurfaceUnchangedAndPersistNothing(
            String scenario, ReasonCode expected, LongFunction<PlaceOrderCommand> request) {
        long b = base("Base", "100.00", 50);

        assertThatThrownBy(() -> placement.placeOrder(request.apply(b)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.reason()).isEqualTo(expected));

        assertNothingPersisted();
    }

    @Test
    void aFailureAfterTheReservationRollsBackOrderItemsReservationAndKey() {
        long b = base("Base", "100.00", 5);
        PlaceOrderCommand cmd = command(line(b, 2));
        TransactionTemplate outer = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            placement.placeInCurrentTransaction(cmd);
            entityManager.flush();
            assertThat(reserved(b)).as("reserved inside the open transaction").isEqualTo(2);
            throw new IllegalStateException("boom after the reservation");
        })).isInstanceOf(IllegalStateException.class);

        assertNothingPersisted();
        // The idempotency key left no trace: the same request now succeeds.
        placement.placeOrder(cmd);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(reserved(b)).isEqualTo(2);
    }

    @Test
    void placingInAReadOnlyTransactionIsRejected() {
        long b = base("Base", "100.00", 5);
        PlaceOrderCommand cmd = command(line(b, 1));
        TransactionTemplate readOnly = new TransactionTemplate(txManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> placement.placeInCurrentTransaction(cmd)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
        assertNothingPersisted();
    }

    @Test
    void placingOutsideATransactionIsRejected() {
        long b = base("Base", "100.00", 5);

        assertThatThrownBy(() -> placement.placeInCurrentTransaction(command(line(b, 1))))
                .isInstanceOf(IllegalStateException.class);
        assertNothingPersisted();
    }
}
