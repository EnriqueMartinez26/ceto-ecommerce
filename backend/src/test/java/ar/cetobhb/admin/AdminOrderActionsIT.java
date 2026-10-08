package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.service.AdminOrderService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderDetail;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BuyerFixtures;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The admin order actions (AOR-04, AOR-05, AOR-06 service side, AUD-02, AUD-03): each one runs the lifecycle call,
 * writes exactly one audit row and returns the updated detail, all in one transaction.
 */
class AdminOrderActionsIT extends AbstractPostgresIT {

    @Autowired
    private AdminOrderService admin;

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

    private long base;

    @BeforeEach
    void setUp() {
        base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 10);
    }

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long order(int quantity) {
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), BuyerFixtures.freshBuyer(),
                List.of(new PlaceOrderCommand.Line(base, quantity)))).orderId();
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private String status(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int auditRows() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
    }

    private Map<String, Object> onlyAuditRow() {
        assertThat(auditRows()).isEqualTo(1);
        return jdbc.queryForMap("SELECT * FROM admin_audit_log");
    }

    private void assertAudit(String action, long orderId, String reason) {
        Map<String, Object> row = onlyAuditRow();
        assertThat(row.get("action")).isEqualTo(action);
        assertThat(row.get("entity_type")).isEqualTo("ORDER");
        assertThat(row.get("entity_id")).isEqualTo(orderId);
        assertThat(row.get("reason")).isEqualTo(reason);
        assertThat(row.get("before_value")).isNull();
        assertThat(row.get("after_value")).isNull();
        assertThat(((Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void payCommitsTheSaleWritesOneAuditRowAndReturnsTheDetail() {
        long id = order(3);
        clock.set(MutableClock.START.plus(Duration.ofMinutes(7)));

        OrderDetail detail = admin.pay(id);

        assertThat(detail.id()).isEqualTo(id);
        assertThat(detail.status()).isEqualTo(OrderStatus.PAGADO);
        assertThat(detail.paidAt()).isEqualTo(clock.instant());
        assertThat(status(id)).isEqualTo("PAGADO");
        assertThat(onHand()).isEqualTo(7);
        assertThat(reserved()).isZero();
        assertAudit("ORDER_PAID", id, null);
    }

    @Test
    void dispatchAndDeliverWriteOneAuditRowEach() {
        long id = order(1);
        admin.pay(id);

        OrderDetail dispatched = admin.dispatch(id);

        assertThat(dispatched.status()).isEqualTo(OrderStatus.DESPACHADO);
        assertThat(dispatched.dispatchedAt()).isEqualTo(clock.instant());
        assertThat(auditRows()).isEqualTo(2);

        OrderDetail delivered = admin.deliver(id);

        assertThat(delivered.status()).isEqualTo(OrderStatus.ENTREGADO);
        assertThat(delivered.deliveredAt()).isEqualTo(clock.instant());
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log ORDER BY id", String.class))
                .containsExactly("ORDER_PAID", "ORDER_DISPATCHED", "ORDER_DELIVERED");
        assertThat(onHand()).isEqualTo(9);
        assertThat(reserved()).isZero();
    }

    @Test
    void cancellingAPendingOrderReleasesTheReservationWithTheAdminReason() {
        long id = order(4);

        OrderDetail detail = admin.cancel(id);

        assertThat(detail.status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(detail.cancelReason()).isEqualTo(CancelReason.CANCELLED_BY_ADMIN);
        assertThat(onHand()).isEqualTo(10);
        assertThat(reserved()).isZero();
        assertAudit("ORDER_CANCELLED", id, "CANCELLED_BY_ADMIN");
    }

    @Test
    void cancellingAPaidOrderReturnsTheUnitsToOnHand() {
        long id = order(4);
        admin.pay(id);
        assertThat(onHand()).isEqualTo(6);

        OrderDetail detail = admin.cancel(id);

        assertThat(detail.status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(onHand()).isEqualTo(10);
        assertThat(reserved()).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log ORDER BY id", String.class))
                .containsExactly("ORDER_PAID", "ORDER_CANCELLED");
    }

    @Test
    void dispatchingAPendingOrderIsIllegalAndLeavesStatusStockAndAuditUntouched() {
        long id = order(2);

        assertThatThrownBy(() -> admin.dispatch(id)).isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertThat(onHand()).isEqualTo(10);
        assertThat(reserved()).isEqualTo(2);
        assertThat(auditRows()).isZero();
    }

    @Test
    void everyActionOnATerminalOrderIsIllegalWithNoEffect() {
        long delivered = order(1);
        admin.pay(delivered);
        admin.dispatch(delivered);
        admin.deliver(delivered);
        long cancelled = order(1);
        admin.cancel(cancelled);
        int auditBefore = auditRows();
        int onHandBefore = onHand();
        int reservedBefore = reserved();

        for (long id : new long[] {delivered, cancelled}) {
            assertThatThrownBy(() -> admin.pay(id)).isInstanceOf(IllegalOrderTransitionException.class);
            assertThatThrownBy(() -> admin.dispatch(id)).isInstanceOf(IllegalOrderTransitionException.class);
            assertThatThrownBy(() -> admin.deliver(id)).isInstanceOf(IllegalOrderTransitionException.class);
            assertThatThrownBy(() -> admin.cancel(id)).isInstanceOf(IllegalOrderTransitionException.class);
        }

        assertThat(status(delivered)).isEqualTo("ENTREGADO");
        assertThat(status(cancelled)).isEqualTo("CANCELADO");
        assertThat(auditRows()).isEqualTo(auditBefore);
        assertThat(onHand()).isEqualTo(onHandBefore);
        assertThat(reserved()).isEqualTo(reservedBefore);
    }

    @Test
    void illegalEdgesAtTheFacadeThrowAndLeaveAuditStockAndStatusUntouched() {
        long dispatched = order(2);
        admin.pay(dispatched);
        admin.dispatch(dispatched);
        long paid = order(1);
        admin.pay(paid);
        long pending = order(1);
        int auditBefore = auditRows();
        int onHandBefore = onHand();
        int reservedBefore = reserved();

        assertThatThrownBy(() -> admin.cancel(dispatched)).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> admin.pay(dispatched)).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> admin.deliver(paid)).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> admin.dispatch(pending)).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> admin.deliver(pending)).isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(status(dispatched)).isEqualTo("DESPACHADO");
        assertThat(status(paid)).isEqualTo("PAGADO");
        assertThat(status(pending)).isEqualTo("PAGO_PENDIENTE");
        assertThat(auditRows()).isEqualTo(auditBefore);
        assertThat(onHand()).isEqualTo(onHandBefore);
        assertThat(reserved()).isEqualTo(reservedBefore);
    }

    @Test
    void payingTwiceIsAnIllegalTransitionAndDoesNotDecrementStockTwice() {
        long id = order(2);
        admin.pay(id);

        assertThatThrownBy(() -> admin.pay(id)).isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(onHand()).isEqualTo(8);
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    void anUnknownIdThrowsOrderNotFoundOnEveryActionAndWritesNothing() {
        assertThatThrownBy(() -> admin.pay(987_654L)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> admin.dispatch(987_654L)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> admin.deliver(987_654L)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> admin.cancel(987_654L)).isInstanceOf(OrderNotFoundException.class);

        assertThat(auditRows()).isZero();
    }

    @Test
    void anAuditInsertThatFailsRollsTheTransitionAndTheStockBack() {
        long id = order(3);
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_audit_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'audit refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER fail_audit_for_test BEFORE INSERT ON admin_audit_log FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_audit_for_test()");
            assertThatThrownBy(() -> admin.pay(id)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> admin.cancel(id)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_audit_for_test ON admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_audit_for_test()");
        }

        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertThat(jdbc.queryForObject("SELECT paid_at FROM orders WHERE id = ?", Timestamp.class, id)).isNull();
        assertThat(onHand()).isEqualTo(10);
        assertThat(reserved()).isEqualTo(3);
        assertThat(auditRows()).isZero();
    }

    @Test
    void aFaultAtCommitTimeAfterTheDomainChangeAndTheAuditPersistsNeitherOfThem() {
        long id = order(3);
        // A deferred constraint trigger fires at COMMIT, after the order update, the stock update and the audit insert.
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_at_commit_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'commit refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE CONSTRAINT TRIGGER fail_at_commit_for_test AFTER INSERT ON admin_audit_log"
                    + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_at_commit_for_test()");
            assertThatThrownBy(() -> admin.pay(id)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_at_commit_for_test ON admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_at_commit_for_test()");
        }

        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertThat(onHand()).isEqualTo(10);
        assertThat(reserved()).isEqualTo(3);
        assertThat(auditRows()).isZero();
    }

    @Test
    void aCallerThatRollsBackTakesTheAuditRowAndTheTransitionWithIt() {
        long id = order(3);

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            admin.pay(id);
            throw new IllegalStateException("caller failed after the action");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(status(id)).isEqualTo("PAGO_PENDIENTE");
        assertThat(onHand()).isEqualTo(10);
        assertThat(auditRows()).isZero();
    }
}
