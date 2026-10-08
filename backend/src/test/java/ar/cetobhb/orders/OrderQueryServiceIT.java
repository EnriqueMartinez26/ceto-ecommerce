package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.ProductAdminView;
import ar.cetobhb.catalog.domain.ProductEditData;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.OrderSummary;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderDetail;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderListQuery;
import ar.cetobhb.orders.service.OrderPage;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.OrderQueryService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BuyerFixtures;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** AOR-01 / AOR-03 / AOR-08 / ACAT-04: the admin order read model over real placed orders. */
class OrderQueryServiceIT extends AbstractPostgresIT {

    private static final String NAME = "Maria Perez";
    private static final String ADDRESS = "Calle Falsa 123";

    @Autowired
    private OrderQueryService query;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private OrderLifecycleService lifecycle;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long base;

    @BeforeEach
    void setUp() {
        base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 1000);
    }

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long order(String phone, int quantity) {
        Buyer buyer = new Buyer(NAME, phone, ADDRESS, ShippingZone.CORDOBA_CAPITAL);
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), buyer,
                List.of(new PlaceOrderCommand.Line(base, quantity)))).orderId();
    }

    private long order() {
        return order(BuyerFixtures.freshPhone(), 1);
    }

    private OrderPage list(OrderStatus status, String q, int size, Long after) {
        return query.list(new OrderListQuery(status, q, size, after));
    }

    private List<Long> ids(OrderPage page) {
        return page.rows().stream().map(OrderSummary::id).toList();
    }

    @Test
    void listPagesNewestFirstAndNextAfterIsNullOnTheLastPage() {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(order());
        }

        OrderPage first = list(null, null, 2, null);
        OrderPage second = list(null, null, 2, first.nextAfter());
        OrderPage third = list(null, null, 2, second.nextAfter());

        assertThat(ids(first)).containsExactly(created.get(4), created.get(3));
        assertThat(first.nextAfter()).isEqualTo(created.get(3));
        assertThat(ids(second)).containsExactly(created.get(2), created.get(1));
        assertThat(second.nextAfter()).isEqualTo(created.get(1));
        assertThat(ids(third)).containsExactly(created.get(0));
        assertThat(third.nextAfter()).isNull();
    }

    @Test
    void aFullLastPageHasNoNextAfter() {
        long a = order();
        long b = order();

        OrderPage page = list(null, null, 2, null);

        assertThat(ids(page)).containsExactly(b, a);
        assertThat(page.nextAfter()).isNull();
    }

    @Test
    void anEmptyDatabaseGivesAnEmptyPage() {
        OrderPage page = list(null, null, 20, null);

        assertThat(page.rows()).isEmpty();
        assertThat(page.nextAfter()).isNull();
    }

    @Test
    void theStatusFilterKeepsOnlyThatStatus() {
        long pending = order();
        long paid = order();
        lifecycle.markPaid(paid);

        assertThat(ids(list(OrderStatus.PAGADO, null, 20, null))).containsExactly(paid);
        assertThat(ids(list(OrderStatus.PAGO_PENDIENTE, null, 20, null))).containsExactly(pending);
    }

    @Test
    void searchByOrderNumberOrByDigitsFindsOnlyThatOrder() {
        order();
        long target = order();
        order();
        String number = jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class, target);
        String digits = String.valueOf(Integer.parseInt(number.substring(5)));

        assertThat(ids(list(null, number, 20, null))).containsExactly(target);
        assertThat(ids(list(null, digits, 20, null))).containsExactly(target);
    }

    @Test
    void searchByPhoneAcceptsAnyWrittenFormAndMatchesTheExactNumber() {
        order("+5493515550198", 1);
        long target = order("+5493515550199", 1);

        assertThat(ids(list(null, "351 555-0199", 20, null))).containsExactly(target);
        assertThat(ids(list(null, "+54 9 351 555 0199", 20, null))).containsExactly(target);
        assertThat(ids(list(null, "3515550199", 20, null))).containsExactly(target);
        assertThat(list(null, "351 555-019", 20, null).rows()).isEmpty();
    }

    @Test
    void unparseableOrNonMatchingSearchTextIsAnEmptyPageNotAnError() {
        order();

        assertThat(list(null, "CETO-0001", 20, null).rows()).isEmpty();
        assertThat(list(null, "garbage", 20, null).rows()).isEmpty();
        assertThat(list(null, "999999", 20, null).rows()).isEmpty();
        assertThat(list(null, "%", 20, null).rows()).isEmpty();
    }

    @Test
    void aBlankSearchTextMeansNoSearch() {
        long id = order();

        assertThat(ids(list(null, "   ", 20, null))).containsExactly(id);
        assertThat(ids(list(null, "", 20, null))).containsExactly(id);
    }

    @Test
    void statusAndSearchCompose() {
        long paid = order("+5493515550199", 1);
        lifecycle.markPaid(paid);
        long pending = order("+5493515550199", 1);

        assertThat(ids(list(OrderStatus.PAGADO, "3515550199", 20, null))).containsExactly(paid);
        assertThat(ids(list(OrderStatus.PAGO_PENDIENTE, "3515550199", 20, null))).containsExactly(pending);
        assertThat(list(OrderStatus.CANCELADO, "3515550199", 20, null).rows()).isEmpty();
    }

    @Test
    void listRowsCarryTheBuyerFieldsButNeverTheAddress() {
        long id = order("+5493515550199", 2);

        OrderSummary row = list(null, null, 20, null).rows().get(0);

        assertThat(row.id()).isEqualTo(id);
        assertThat(row.buyerName()).isEqualTo(NAME);
        assertThat(row.buyerPhoneE164()).isEqualTo("+5493515550199");
        assertThat(row.total()).isEqualByComparingTo("200.00");
        assertThat(row.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(row.reservedUntil()).isNotNull();
        assertThat(row.toString()).doesNotContain(NAME).doesNotContain("+5493515550199").doesNotContain(ADDRESS);
        assertThat(OrderSummary.class.getRecordComponents()).extracting(c -> c.getName().toLowerCase())
                .noneMatch(n -> n.contains("address"));
    }

    @Test
    void listRowsOfNonPendingOrdersHaveNoReservedUntil() {
        long id = order();
        lifecycle.markPaid(id);

        assertThat(list(null, null, 20, null).rows().get(0).reservedUntil()).isNull();
    }

    @Test
    void theListQueryBoundsItsInputs() {
        assertThatThrownBy(() -> new OrderListQuery(null, null, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderListQuery(null, null, 51, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderListQuery(null, null, 20, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderListQuery(null, null, 20, 9_007_199_254_740_992L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderListQuery(null, "x".repeat(65), 20, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new OrderListQuery(null, "x".repeat(64), 50, 9_007_199_254_740_991L).size()).isEqualTo(50);
        assertThat(new OrderListQuery(null, null, 1, 1L).after()).isEqualTo(1L);
    }

    @Test
    void detailOfAPendingOrderCarriesBuyerLinesTimestampsAndTheCountdown() {
        long id = order("+5493515550199", 2);
        clock.set(MutableClock.START.plus(Duration.ofHours(1)));

        OrderDetail detail = query.detail(id);

        assertThat(detail.id()).isEqualTo(id);
        assertThat(detail.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(detail.buyerName()).isEqualTo(NAME);
        assertThat(detail.buyerPhoneE164()).isEqualTo("+5493515550199");
        assertThat(detail.shippingAddress()).isEqualTo(ADDRESS);
        assertThat(detail.zone()).isEqualTo(ShippingZone.CORDOBA_CAPITAL);
        assertThat(detail.total()).isEqualByComparingTo("200.00");
        assertThat(detail.createdAt()).isEqualTo(MutableClock.START);
        assertThat(detail.reservedUntil()).isEqualTo(MutableClock.START.plus(Duration.ofHours(48)));
        assertThat(detail.secondsLeft()).isEqualTo(47L * 3600);
        assertThat(detail.paidAt()).isNull();
        assertThat(detail.cancelReason()).isNull();
        assertThat(detail.lines()).hasSize(1);
        OrderDetail.Line line = detail.lines().get(0);
        assertThat(line.lineNo()).isEqualTo(1);
        assertThat(line.productId()).isEqualTo(base);
        assertThat(line.productName()).isEqualTo("Frasco");
        assertThat(line.combo()).isFalse();
        assertThat(line.unitPrice()).isEqualByComparingTo("100.00");
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.baseUnitsPerUnit()).isEqualTo(1);
        assertThat(line.baseUnitsTotal()).isEqualTo(2);
        assertThat(line.lineTotal()).isEqualByComparingTo("200.00");
        assertThat(detail.toString()).doesNotContain(NAME).doesNotContain(ADDRESS).doesNotContain("5493515550199");
    }

    @Test
    void anOverdueButUnexpiredOrderShowsZeroSecondsAndStaysPayable() {
        long id = order();
        clock.set(MutableClock.START.plus(Duration.ofHours(72)));

        OrderDetail detail = query.detail(id);

        assertThat(detail.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(detail.secondsLeft()).isZero();
        assertThat(detail.reservedUntil()).isNotNull();
        lifecycle.markPaid(id);
        assertThat(query.detail(id).status()).isEqualTo(OrderStatus.PAGADO);
    }

    @Test
    void aPaidOrderHasNoReservationFieldsAndItsPaidTimestamp() {
        long id = order();
        clock.set(MutableClock.START.plus(Duration.ofMinutes(5)));
        lifecycle.markPaid(id);

        OrderDetail detail = query.detail(id);

        assertThat(detail.status()).isEqualTo(OrderStatus.PAGADO);
        assertThat(detail.reservedUntil()).isNull();
        assertThat(detail.secondsLeft()).isNull();
        assertThat(detail.paidAt()).isEqualTo(MutableClock.START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void aCancelledOrderShowsItsReasonAndTimestamp() {
        long id = order();
        lifecycle.cancel(id, CancelReason.CANCELLED_BY_ADMIN);

        OrderDetail detail = query.detail(id);

        assertThat(detail.status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(detail.cancelReason()).isEqualTo(CancelReason.CANCELLED_BY_ADMIN);
        assertThat(detail.cancelledAt()).isEqualTo(MutableClock.START);
        assertThat(detail.secondsLeft()).isNull();
    }

    @Test
    void detailOfAnUnknownIdThrowsOrderNotFound() {
        assertThatThrownBy(() -> query.detail(987_654L)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void afterAProductPriceEditTheDetailStillShowsTheOriginalPriceAndTotal() {
        long id = order("+5493515550199", 2);
        ProductAdminView before = catalog.listAll(10).get(0);
        catalog.update(base, before.version(), new ProductEditData("Frasco", new BigDecimal("150.00"), null));

        OrderDetail detail = query.detail(id);

        assertThat(detail.lines().get(0).unitPrice()).isEqualByComparingTo("100.00");
        assertThat(detail.lines().get(0).productName()).isEqualTo("Frasco");
        assertThat(detail.total()).isEqualByComparingTo("200.00");
        assertThat(list(null, null, 20, null).rows().get(0).total()).isEqualByComparingTo("200.00");
    }

    @Test
    void detailAfterAnUpdateInTheSameTransactionSeesTheNewState() {
        long id = order();
        long other = order();

        // Reads inside one transaction after a write must not return a stale view (no cached entity of the order).
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OrderDetail inside = tx.execute(status -> {
            lifecycle.markPaid(id);
            return query.detail(id);
        });

        assertThat(inside.status()).isEqualTo(OrderStatus.PAGADO);
        assertThat(query.detail(other).status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
    }

}
