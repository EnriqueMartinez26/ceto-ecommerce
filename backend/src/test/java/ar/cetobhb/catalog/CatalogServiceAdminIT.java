package ar.cetobhb.catalog;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static ar.cetobhb.support.CatalogFixtures.productWithCompareAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.ProductAdminView;
import ar.cetobhb.catalog.domain.ProductChange;
import ar.cetobhb.catalog.domain.ProductEditData;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.StaleProductVersionException;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.checkout.domain.StorefrontProduct;
import ar.cetobhb.checkout.service.StorefrontService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.StockReconciliation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** ACAT-01, ACAT-04, ACAT-05, CAT-A1, CAT-A3: the admin catalog use cases of CatalogService on a real Postgres. */
class CatalogServiceAdminIT extends AbstractPostgresIT {

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private OrderLifecycleService lifecycle;

    @Autowired
    private StorefrontService storefront;

    @Autowired
    private JdbcTemplate jdbc;

    private static ProductEditData edit(String name, String price, String compareAt) {
        return new ProductEditData(name, new BigDecimal(price), compareAt == null ? null : new BigDecimal(compareAt));
    }

    private long baseWithStock(String name, String price, int onHand) {
        long id = catalog.createBase(product(name, price)).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private PlacedOrder order(long productId, int quantity) {
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(),
                List.of(new PlaceOrderCommand.Line(productId, quantity))));
    }

    private Map<String, Object> stock(long id) {
        return jdbc.queryForMap("SELECT on_hand, reserved FROM inventory_stock WHERE product_id = ?", id);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private long version(long id) {
        return jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id);
    }

    private boolean listedInStorefront(long id) {
        return storefront.listProducts().stream().map(StorefrontProduct::product).anyMatch(p -> p.id() == id);
    }

    @Test
    void listAllIncludesInactiveProductsAndCombosOrderedByIdAndHonoursTheCap() {
        long base = catalog.createBase(product("Base", "100.00")).id();
        long hidden = catalog.createBase(product("Hidden", "50.00")).id();
        long combo = catalog.createCombo(product("Combo", "270.00"), base, 3).id();
        catalog.deactivate(hidden);

        List<ProductAdminView> all = catalog.listAll(10);

        assertThat(all).extracting(ProductAdminView::id).containsExactly(base, hidden, combo);
        assertThat(all).extracting(ProductAdminView::active).containsExactly(true, false, true);
        assertThat(all.get(2).comboBaseProductId()).isEqualTo(base);
        assertThat(all.get(2).comboBaseUnits()).isEqualTo(3);
        assertThat(all.get(0).version()).isZero();
        assertThat(catalog.listAll(2)).extracting(ProductAdminView::id).containsExactly(base, hidden);
    }

    @Test
    void findingAnUnknownProductIsNotFound() {
        assertThatThrownBy(() -> catalog.findAnyIncludingInactive(99_999L)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void reactivatingAComboWhoseBaseIsInactiveIsRejectedAndNothingChanges() {
        long base = catalog.createBase(product("Base", "100.00")).id();
        long combo = catalog.createCombo(product("Combo", "270.00"), base, 3).id();
        catalog.setActive(combo, false);
        catalog.setActive(base, false);
        long versionBefore = version(combo);

        assertThatThrownBy(() -> catalog.setActive(combo, true))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_COMBO_LINK));

        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, combo)).isFalse();
        assertThat(version(combo)).isEqualTo(versionBefore);
        // Once the base is back, the combo can be reactivated.
        assertThat(catalog.setActive(base, true)).isTrue();
        assertThat(catalog.setActive(combo, true)).isTrue();
    }

    @Test
    void anAlreadyActiveComboWithAnInactiveBaseIsAnIdempotentNoOp() {
        long base = catalog.createBase(product("Base", "100.00")).id();
        long combo = catalog.createCombo(product("Combo", "270.00"), base, 3).id();
        catalog.setActive(base, false);

        assertThat(catalog.setActive(combo, true)).isFalse();
    }

    @Test
    void anEditWithTheCurrentVersionAppliesAndReturnsTheNewVersion() {
        long id = catalog.createBase(productWithCompareAt("Base", "100.00", "130.00")).id();

        ProductChange change = catalog.update(id, 0, edit("Base v2", "150.00", "200.00"));

        assertThat(change.priceChanged()).isTrue();
        assertThat(change.otherChanged()).isTrue();
        assertThat(change.priceBefore()).isEqualByComparingTo("100.00");
        assertThat(change.priceAfter()).isEqualByComparingTo("150.00");
        assertThat(change.view().version()).isEqualTo(1);
        assertThat(change.view().name()).isEqualTo("Base v2");
        assertThat(change.view().compareAtPrice()).isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id)).isEqualTo(1);
    }

    @Test
    void anEditThatChangesNothingDoesNotBumpTheVersion() {
        long id = catalog.createBase(product("Base", "100.00")).id();

        ProductChange change = catalog.update(id, 0, edit("Base", "100.00", null));

        assertThat(change.priceChanged()).isFalse();
        assertThat(change.otherChanged()).isFalse();
        assertThat(change.view().version()).isZero();
    }

    @Test
    void aStaleExpectedVersionIsRejectedAndNothingChanges() {
        long id = catalog.createBase(product("Base", "100.00")).id();
        catalog.update(id, 0, edit("Base", "120.00", null));

        assertThatThrownBy(() -> catalog.update(id, 0, edit("Other", "999.00", null)))
                .isInstanceOf(StaleProductVersionException.class);

        ProductAdminView current = catalog.listAll(10).get(0);
        assertThat(current.name()).isEqualTo("Base");
        assertThat(current.price()).isEqualByComparingTo("120.00");
        assertThat(current.version()).isEqualTo(1);
    }

    @Test
    void anInvalidEditIsRejectedWithoutTouchingTheRow() {
        long id = catalog.createBase(product("Base", "100.00")).id();

        assertThatThrownBy(() -> catalog.update(id, 0, edit("Base", "100.00", "100.00")))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_PRICE_PAIR));
        assertThatThrownBy(() -> catalog.update(id, 0, edit("Bad\u0007name", "100.00", null)))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_CHARACTERS));

        assertThat(catalog.listAll(10).get(0).version()).isZero();
    }

    @Test
    void anEditOfAnUnknownProductIsNotFound() {
        assertThatThrownBy(() -> catalog.update(99_999L, 0, edit("X", "10.00", null)))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void theNameIsStoredRawWithoutEscaping() {
        long id = catalog.createBase(product("<b>Tom & Jerry</b>", "10.00")).id();

        catalog.update(id, 0, edit("<i>Café & co</i>", "10.00", null));

        assertThat(catalog.listAll(1).get(0).name()).isEqualTo("<i>Café & co</i>");
    }

    @Test
    void twoConcurrentEditsFromTheSameReadNeverOverwriteEachOther() {
        long id = catalog.createBase(product("Base", "100.00")).id();
        List<Callable<ProductChange>> tasks = List.of(
                () -> catalog.update(id, 0, edit("First", "110.00", null)),
                () -> catalog.update(id, 0, edit("Second", "120.00", null)));

        List<BurstRunner.Outcome<ProductChange>> outcomes = BurstRunner.run(tasks);

        assertThat(outcomes.stream().filter(BurstRunner.Outcome::succeeded)).hasSize(1);
        BurstRunner.Outcome<ProductChange> loser = outcomes.stream().filter(o -> !o.succeeded()).findFirst()
                .orElseThrow();
        assertThat(loser.error()).satisfiesAnyOf(
                e -> assertThat(e).isInstanceOf(StaleProductVersionException.class),
                e -> assertThat(e).isInstanceOf(OptimisticLockingFailureException.class));
        ProductChange winner = outcomes.stream().filter(BurstRunner.Outcome::succeeded).findFirst().orElseThrow()
                .value();
        ProductAdminView stored = catalog.listAll(1).get(0);
        assertThat(stored.name()).isEqualTo(winner.view().name());
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    void setActiveIsIdempotentAndReportsWhetherItChangedAnything() {
        long id = catalog.createBase(product("Base", "100.00")).id();

        assertThat(catalog.setActive(id, true)).isFalse();
        assertThat(catalog.setActive(id, false)).isTrue();
        assertThat(catalog.setActive(id, false)).isFalse();
        assertThat(catalog.setActive(id, true)).isTrue();
        assertThat(catalog.setActive(id, true)).isFalse();
        assertThat(jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id)).isEqualTo(2);
        assertThatThrownBy(() -> catalog.setActive(99_999L, true)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void anEditedPriceLeavesExistingOrderLinesUntouchedAndNewOrdersUseTheNewPrice() {
        long id = baseWithStock("Base", "100.00", 10);
        PlacedOrder before = order(id, 2);

        catalog.update(id, 0, edit("Base", "150.00", null));
        PlacedOrder after = order(id, 1);

        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id = ?", BigDecimal.class,
                before.orderId())).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class,
                before.orderId())).isEqualByComparingTo("200.00");
        assertThat(after.total()).isEqualByComparingTo("150.00");
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    @Test
    void deactivatingAProductWithAPendingOrderLeavesTheOrderAndTheReservationAlone() {
        long id = baseWithStock("Base", "100.00", 10);
        PlacedOrder pending = order(id, 3);

        catalog.setActive(id, false);

        assertThat(orderStatus(pending.orderId())).isEqualTo("PAGO_PENDIENTE");
        assertThat(stock(id)).containsEntry("on_hand", 10).containsEntry("reserved", 3);
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    @Test
    void anOrderOfADeactivatedProductCanStillBePaid() {
        long id = baseWithStock("Base", "100.00", 10);
        PlacedOrder pending = order(id, 3);
        catalog.setActive(id, false);

        lifecycle.markPaid(pending.orderId());

        assertThat(orderStatus(pending.orderId())).isEqualTo("PAGADO");
        assertThat(stock(id)).containsEntry("on_hand", 7).containsEntry("reserved", 0);
    }

    @Test
    void anOrderOfADeactivatedProductCanStillBeCancelled() {
        long id = baseWithStock("Base", "100.00", 10);
        PlacedOrder pending = order(id, 3);
        catalog.setActive(id, false);

        lifecycle.cancel(pending.orderId(), CancelReason.CANCELLED_BY_ADMIN);

        assertThat(orderStatus(pending.orderId())).isEqualTo("CANCELADO");
        assertThat(stock(id)).containsEntry("on_hand", 10).containsEntry("reserved", 0);
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    @Test
    void theStorefrontHidesADeactivatedProductAndShowsItAgainWhenReactivated() {
        long id = baseWithStock("Base", "100.00", 5);
        assertThat(listedInStorefront(id)).isTrue();

        catalog.setActive(id, false);
        assertThat(listedInStorefront(id)).isFalse();

        catalog.setActive(id, true);
        assertThat(listedInStorefront(id)).isTrue();
    }
}
