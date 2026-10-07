package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.StockReconciliation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Idempotent placement (CLAUDE.md rule 10, D-20261006-11, D-20261007-04) with real commits. */
class OrderIdempotencyIT extends AbstractPostgresIT {

    private static final int THREADS = 10;
    private static final Buyer BUYER =
            new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long base(int onHand) {
        long id = catalog.createBase(product("Base", "100.00")).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private static PlaceOrderCommand command(UUID key, Buyer buyer, long productId, int quantity) {
        return new PlaceOrderCommand(key.toString(), buyer, List.of(new PlaceOrderCommand.Line(productId, quantity)));
    }

    private int reserved(long productId) {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class,
                productId);
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    @Test
    void sameKeyAndPayloadReturnsTheExistingOrderWithoutReservingAgain() {
        long b = base(10);
        UUID key = UUID.randomUUID();

        PlacedOrder first = placement.placeOrder(command(key, BUYER, b, 2));
        PlacedOrder second = placement.placeOrder(command(key, BUYER, b, 2));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(second.orderNumber()).isEqualTo(first.orderNumber());
        assertThat(second.total()).isEqualByComparingTo(first.total());
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(b)).isEqualTo(2);
    }

    @Test
    void equivalentPhoneFormattingIsTheSamePayload() {
        long b = base(10);
        UUID key = UUID.randomUUID();
        Buyer spaced = new Buyer("Maria Perez", "+54 9 351 555-0000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);

        PlacedOrder first = placement.placeOrder(command(key, BUYER, b, 1));
        PlacedOrder second = placement.placeOrder(command(key, spaced, b, 1));

        assertThat(second.replayed()).isTrue();
        assertThat(second.orderId()).isEqualTo(first.orderId());
    }

    @Test
    void sameKeyWithAnotherQuantityBuyerOrZoneIsAConflictAndChangesNothing() {
        long b = base(10);
        UUID key = UUID.randomUUID();
        PlacedOrder first = placement.placeOrder(command(key, BUYER, b, 2));

        Buyer otherName = new Buyer("Ana Gomez", "+5493515550000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);
        Buyer otherZone = new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.RESTO_DEL_PAIS);
        for (PlaceOrderCommand different : List.of(command(key, BUYER, b, 3), command(key, otherName, b, 2),
                command(key, otherZone, b, 2))) {
            assertThatThrownBy(() -> placement.placeOrder(different)).isInstanceOf(IdempotencyConflictException.class);
        }

        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(b)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, first.orderId()))
                .isEqualByComparingTo("200.00");
    }

    @Test
    void aFailedPlacementDoesNotBurnTheKey() {
        long b = base(1);
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> placement.placeOrder(command(key, BUYER, b, 2)))
                .isInstanceOf(InsufficientStockException.class);
        inventory.adjust(b, 5);

        PlacedOrder placed = placement.placeOrder(command(key, BUYER, b, 2));

        assertThat(placed.replayed()).isFalse();
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(b)).isEqualTo(2);
    }

    @Test
    void retryingWithTheKeyOfACancelledOrderReturnsThatOrder() {
        long b = base(10);
        UUID key = UUID.randomUUID();
        PlacedOrder first = placement.placeOrder(command(key, BUYER, b, 2));
        // The cancellation service arrives in a later slice: SQL fixture, releasing the reservation by hand.
        jdbc.update("UPDATE orders SET status = 'CANCELADO', cancel_reason = 'CANCELLED_BY_ADMIN',"
                + " cancelled_at = now() WHERE id = ?", first.orderId());
        jdbc.update("UPDATE inventory_stock SET reserved = 0 WHERE product_id = ?", b);

        PlacedOrder again = placement.placeOrder(command(key, BUYER, b, 2));

        assertThat(again.replayed()).isTrue();
        assertThat(again.orderId()).isEqualTo(first.orderId());
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(b)).isZero();
    }

    @Test
    void replayAfterTheProductWasDeactivatedReturnsTheOriginalOrder() {
        long b = base(10);
        UUID key = UUID.randomUUID();
        PlacedOrder first = placement.placeOrder(command(key, BUYER, b, 2));
        catalog.deactivate(b);

        PlacedOrder again = placement.placeOrder(command(key, BUYER, b, 2));

        assertThat(again.replayed()).isTrue();
        assertThat(again.orderId()).isEqualTo(first.orderId());
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(b)).isEqualTo(2);
    }

    @Test
    void replayAfterTheStockWasExhaustedByOtherOrdersReturnsTheOriginalOrder() {
        long b = base(3);
        UUID key = UUID.randomUUID();
        PlacedOrder first = placement.placeOrder(command(key, BUYER, b, 2));
        placement.placeOrder(command(UUID.randomUUID(), BUYER, b, 1));
        assertThatThrownBy(() -> placement.placeOrder(command(UUID.randomUUID(), BUYER, b, 1)))
                .isInstanceOf(InsufficientStockException.class);

        PlacedOrder again = placement.placeOrder(command(key, BUYER, b, 2));

        assertThat(again.replayed()).isTrue();
        assertThat(again.orderId()).isEqualTo(first.orderId());
        assertThat(orderCount()).isEqualTo(2);
        assertThat(reserved(b)).isEqualTo(3);
    }

    @Test
    void sameKeyBurstWithTheSamePayloadCreatesOneOrderAndReservesOnce() {
        assertSameKeyBurstCreatesOnce(base(10));
    }

    @Test
    void sameKeyBurstOnTheLastUnitStillCreatesOneOrder() {
        assertSameKeyBurstCreatesOnce(base(1));
    }

    private void assertSameKeyBurstCreatesOnce(long productId) {
        PlaceOrderCommand command = command(UUID.randomUUID(), BUYER, productId, 1);
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> placement.placeOrder(command));
        }

        Report<PlacedOrder> report = BurstRunner.runMeasured(tasks);
        List<Outcome<PlacedOrder>> outcomes = report.outcomes();
        assertThat(report.maxInFlight()).isGreaterThan(1);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        List<PlacedOrder> results = outcomes.stream().map(Outcome::value).toList();
        assertThat(results).extracting(PlacedOrder::orderId).containsOnly(results.get(0).orderId());
        assertThat(results.stream().filter(p -> !p.replayed())).hasSize(1);
        assertThat(results.stream().filter(PlacedOrder::replayed)).hasSize(THREADS - 1);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(productId)).isEqualTo(1);
    }

    @Test
    void sameKeyBurstWithDifferentPayloadsKeepsTheWinnerAndRejectsTheOthers() {
        long b = base(20);
        UUID key = UUID.randomUUID();
        PlaceOrderCommand one = command(key, BUYER, b, 1);
        PlaceOrderCommand two = command(key, BUYER, b, 2);
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            PlaceOrderCommand mine = i % 2 == 0 ? one : two;
            tasks.add(() -> placement.placeOrder(mine));
        }

        Report<PlacedOrder> report = BurstRunner.runMeasured(tasks);
        List<Outcome<PlacedOrder>> outcomes = report.outcomes();
        assertThat(report.maxInFlight()).isGreaterThan(1);

        assertThat(orderCount()).isEqualTo(1);
        long winnerQuantity = jdbc.queryForObject("SELECT quantity FROM order_items", Long.class);
        assertThat(reserved(b)).isEqualTo((int) winnerQuantity);
        for (int i = 0; i < THREADS; i++) {
            boolean winnerPayload = (i % 2 == 0 ? 1 : 2) == winnerQuantity;
            Outcome<PlacedOrder> outcome = outcomes.get(i);
            if (winnerPayload) {
                assertThat(outcome.succeeded()).as("thread %d", i).isTrue();
            } else {
                assertThat(outcome.error()).as("thread %d", i).isInstanceOf(IdempotencyConflictException.class);
            }
        }
        assertThat(outcomes.stream().filter(Outcome::succeeded).map(o -> o.value().orderId()).distinct()).hasSize(1);
        long winnerPayloadThreads = IntStream.range(0, THREADS)
                .filter(i -> (i % 2 == 0 ? 1 : 2) == winnerQuantity).count();
        List<PlacedOrder> successes = outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).toList();
        assertThat(successes).hasSize((int) winnerPayloadThreads);
        assertThat(successes.stream().filter(p -> !p.replayed())).as("exactly one creator").hasSize(1);
    }

    @Test
    void sameKeyBurstWhoseWinnerRollsBackLeavesNoOrderAndDoesNotBurnTheKey() {
        long b = base(2);
        PlaceOrderCommand command = command(UUID.randomUUID(), BUYER, b, 3);
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> placement.placeOrder(command));
        }

        Report<PlacedOrder> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThan(1);
        assertThat(report.outcomes().stream().map(Outcome::error)).hasSize(THREADS)
                .allMatch(e -> e instanceof InsufficientStockException);
        assertThat(orderCount()).isZero();
        assertThat(reserved(b)).isZero();
        inventory.adjust(b, 5);

        PlacedOrder placed = placement.placeOrder(command);

        assertThat(placed.replayed()).isFalse();
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(b)).isEqualTo(3);
    }
}
