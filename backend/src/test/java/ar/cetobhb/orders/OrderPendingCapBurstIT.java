package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.domain.TooManyPendingOrdersException;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BuyerFixtures;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import ar.cetobhb.support.StockReconciliation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency proof of the pending-orders cap (REQ-ABU-01, design AD-H9) with real commits and no @Transactional on
 * the test. Without the per-phone advisory lock, concurrent placements all count the same pending orders and all
 * insert: the distinct-key burst below fails (mutation evidence in the apply progress). Every burst asserts that more
 * than one task was in flight at once.
 */
class OrderPendingCapBurstIT extends AbstractPostgresIT {

    private static final int CAP = 3;
    private static final int THREADS = 10;
    private static final String PHONE = "+5493515550000";

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long base;

    @BeforeEach
    void seed() {
        base = catalog.createBase(product("Base", "100.00")).id();
        inventory.createStock(base, 1000);
    }

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private PlaceOrderCommand command(UUID key, String phone) {
        return new PlaceOrderCommand(key.toString(),
                new Buyer("Maria Perez", phone, "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, 1)));
    }

    private PlacedOrder place(String phone) {
        return placement.placeOrder(command(UUID.randomUUID(), phone));
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private Map<String, Object> sequenceState() {
        return jdbc.queryForMap("SELECT last_value, is_called FROM order_number_seq");
    }


    @RepeatedTest(10)
    void distinctKeysOfOnePhoneInABurstYieldExactlyTheCap() {
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            PlaceOrderCommand command = command(UUID.randomUUID(), PHONE);
            tasks.add(() -> placement.placeOrder(command));
        }

        Report<PlacedOrder> report = BurstRunner.runMeasured(tasks);
        List<Outcome<PlacedOrder>> outcomes = report.outcomes();

        assertThat(report.maxInFlight()).isGreaterThan(1);
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(CAP);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::error))
                .hasSize(THREADS - CAP).allMatch(e -> e instanceof TooManyPendingOrdersException);
        assertThat(orderCount()).isEqualTo(CAP);
        assertThat(reserved()).isEqualTo(CAP);
        assertThat(sequenceState().get("last_value")).isEqualTo((long) CAP);
    }

    @RepeatedTest(5)
    void oneKeyRetriedInABurstBelowTheCapCreatesOneOrderAndNeverRejects() {
        place(PHONE);
        place(PHONE);
        PlaceOrderCommand command = command(UUID.randomUUID(), PHONE);
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> placement.placeOrder(command));
        }

        Report<PlacedOrder> report = BurstRunner.runMeasured(tasks);
        List<Outcome<PlacedOrder>> outcomes = report.outcomes();

        assertThat(report.maxInFlight()).isGreaterThan(1);
        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed())).hasSize(1);
        assertThat(orderCount()).isEqualTo(CAP);
        assertThat(reserved()).isEqualTo(CAP);
    }

    @RepeatedTest(5)
    void oneKeyRetriedInABurstWhileThePhoneIsAtTheCapIsAlwaysAReplayNeverARejection() {
        PlaceOrderCommand command = command(UUID.randomUUID(), PHONE);
        placement.placeOrder(command);
        place(PHONE);
        place(PHONE);
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> placement.placeOrder(command));
        }

        List<Outcome<PlacedOrder>> outcomes = BurstRunner.run(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes).allMatch(o -> o.value().replayed());
        assertThat(orderCount()).isEqualTo(CAP);
    }

    @Test
    void burstsOfDifferentPhonesDoNotShareTheCap() {
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String phone = "+549351555000" + (i % 5);
            PlaceOrderCommand command = command(UUID.randomUUID(), phone);
            tasks.add(() -> placement.placeOrder(command));
        }

        List<Outcome<PlacedOrder>> outcomes = BurstRunner.run(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(orderCount()).isEqualTo(THREADS);
    }

    @Test
    void aReplayNeverWaitsForTheLockOfItsPhone() {
        UUID key = UUID.randomUUID();
        PlacedOrder first = placement.placeOrder(command(key, PHONE));
        TransactionTemplate holder = new TransactionTemplate(transactionManager);

        PlacedOrder replay = holder.execute(status -> {
            orders.lockBuyerPhone(PHONE);
            try {
                // Another thread: this one holds the phone lock until the lambda returns.
                return CompletableFuture.supplyAsync(() -> placement.placeOrder(command(key, PHONE)))
                        .get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("the replay waited for (or failed on) the phone lock", e);
            }
        });

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @RepeatedTest(5)
    void oneKeyAcrossManyPhonesInABurstCreatesOneOrderAndConflictsTheRest() {
        UUID key = UUID.randomUUID();
        List<Callable<PlacedOrder>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            PlaceOrderCommand command = command(key, BuyerFixtures.freshPhone());
            tasks.add(() -> placement.placeOrder(command));
        }

        Report<PlacedOrder> report = BurstRunner.runMeasured(tasks);
        List<Outcome<PlacedOrder>> outcomes = report.outcomes();

        assertThat(report.maxInFlight()).isGreaterThan(1);
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::error))
                .hasSize(THREADS - 1).allMatch(e -> e instanceof IdempotencyConflictException);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved()).isEqualTo(1);
    }
}
