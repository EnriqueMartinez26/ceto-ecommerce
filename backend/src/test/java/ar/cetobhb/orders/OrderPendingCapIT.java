package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.domain.TooManyPendingOrdersException;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REQ-ABU-01: at most {@code max-pending-per-phone} (3 in the test profile, the shipped default) open PAGO_PENDIENTE
 * orders per phone. Sequential rules and the lock primitive; the concurrency proof lives in
 * {@link OrderPendingCapBurstIT} (D-20261007-19, design AD-H9).
 */
class OrderPendingCapIT extends AbstractPostgresIT {

    private static final int CAP = 3;
    private static final String PHONE = "+5493515550000";

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private OrderLifecycleService lifecycle;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MutableClock clock;

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

    private static Buyer buyer(String phone) {
        return new Buyer("Maria Perez", phone, "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);
    }

    private PlaceOrderCommand command(UUID key, String phone, int quantity) {
        return new PlaceOrderCommand(key.toString(), buyer(phone),
                List.of(new PlaceOrderCommand.Line(base, quantity)));
    }

    private PlacedOrder place(String phone) {
        return placement.placeOrder(command(UUID.randomUUID(), phone, 1));
    }

    private void fillTheCap(String phone) {
        for (int i = 0; i < CAP; i++) {
            place(phone);
        }
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

    // ---- sequential rules ----

    @Test
    void thePhoneCanHoldExactlyTheCapOfPendingOrders() {
        fillTheCap(PHONE);

        assertThat(orderCount()).isEqualTo(CAP);
        assertThat(reserved()).isEqualTo(CAP);
    }

    @Test
    void theNextOrderOverTheCapIsRejectedAndConsumesNothing() {
        fillTheCap(PHONE);
        Map<String, Object> sequenceBefore = sequenceState();

        assertThatThrownBy(() -> place(PHONE))
                .isInstanceOf(TooManyPendingOrdersException.class)
                .hasMessage("TOO_MANY_PENDING_ORDERS");

        assertThat(orderCount()).isEqualTo(CAP);
        assertThat(reserved()).isEqualTo(CAP);
        assertThat(sequenceState()).isEqualTo(sequenceBefore);
    }

    @Test
    void anotherPhoneIsNotAffectedByTheCapOfThisOne() {
        fillTheCap(PHONE);

        PlacedOrder other = place("+5493515550001");

        assertThat(other.replayed()).isFalse();
        assertThat(orderCount()).isEqualTo(CAP + 1);
    }

    @Test
    void replayingAKeyAtTheCapReturnsTheExistingOrderInsteadOfRejecting() {
        UUID firstKey = UUID.randomUUID();
        PlacedOrder first = placement.placeOrder(command(firstKey, PHONE, 1));
        place(PHONE);
        place(PHONE);

        PlacedOrder replay = placement.placeOrder(command(firstKey, PHONE, 1));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(orderCount()).isEqualTo(CAP);
    }

    @Test
    void aKeyReusedForAnotherPayloadAtTheCapIsAnIdempotencyConflictNotTheCapError() {
        UUID firstKey = UUID.randomUUID();
        placement.placeOrder(command(firstKey, PHONE, 1));
        place(PHONE);
        place(PHONE);

        assertThatThrownBy(() -> placement.placeOrder(command(firstKey, PHONE, 2)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aCancelledOrderFreesItsSlot() {
        PlacedOrder first = place(PHONE);
        place(PHONE);
        place(PHONE);
        lifecycle.cancel(first.orderId(), CancelReason.CANCELLED_BY_BUYER);

        PlacedOrder fresh = place(PHONE);

        assertThat(fresh.replayed()).isFalse();
    }

    @Test
    void aPaidOrderFreesItsSlot() {
        PlacedOrder first = place(PHONE);
        place(PHONE);
        place(PHONE);
        lifecycle.markPaid(first.orderId());

        PlacedOrder fresh = place(PHONE);

        assertThat(fresh.replayed()).isFalse();
    }

    @Test
    void anExpiredOrderFreesItsSlot() {
        fillTheCap(PHONE);
        clock.set(MutableClock.START.plus(Duration.ofHours(49)));
        assertThat(lifecycle.expireDueOrders(10).expired()).isEqualTo(CAP);

        PlacedOrder fresh = place(PHONE);

        assertThat(fresh.replayed()).isFalse();
    }

    @Test
    void anOrderPastItsReservationDeadlineThatTheJobHasNotExpiredYetStillCountsTowardTheCap() {
        // D-20261007-24: its stock is still reserved, so its slot is still taken until the job cancels it.
        fillTheCap(PHONE);
        clock.set(MutableClock.START.plus(Duration.ofHours(49)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'PAGO_PENDIENTE'"
                + " AND reserved_until < ?", Integer.class, Timestamp.from(clock.instant()))).isEqualTo(CAP);

        assertThatThrownBy(() -> place(PHONE)).isInstanceOf(TooManyPendingOrdersException.class);

        assertThat(orderCount()).isEqualTo(CAP);
        assertThat(reserved()).isEqualTo(CAP);
    }

    @Test
    void formatVariantsOfTheSamePhoneShareOneBucket() {
        place("+54 9 351 555-1234");
        place("0351 15 555-1234");
        place("+5493515551234");

        assertThatThrownBy(() -> place("+54 9 351 5551234")).isInstanceOf(TooManyPendingOrdersException.class);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT buyer_phone_e164) FROM orders", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void placeInCurrentTransactionIsCappedToo() {
        fillTheCap(PHONE);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> placement.placeInCurrentTransaction(command(UUID.randomUUID(), PHONE, 1))))
                .isInstanceOf(TooManyPendingOrdersException.class);
        assertThat(orderCount()).isEqualTo(CAP);
    }

    // ---- the lock itself ----

    @Test
    void lockBuyerPhoneHoldsTheTransactionScopedAdvisoryLockOfThatPhoneOnly() throws SQLException {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            orders.lockBuyerPhone(PHONE);
            assertThat(tryLockFromAnotherSession(PHONE)).as("same phone, other session").isFalse();
            assertThat(tryLockFromAnotherSession("+5493515550001")).as("other phone").isTrue();
        });

        assertThat(tryLockFromAnotherSession(PHONE)).as("released at commit").isTrue();
    }

    /** A brand new connection: a lock held by the test transaction is foreign to it, never re-entered. */
    private boolean tryLockFromAnotherSession(String phone) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, phone);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void lockBuyerPhoneRequiresAnActiveTransaction() {
        assertThatThrownBy(() -> orders.lockBuyerPhone(PHONE))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
}
