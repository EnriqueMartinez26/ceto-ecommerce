package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.service.AdminCatalogService;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.admin.service.AdminOrderService;
import ar.cetobhb.catalog.domain.ProductAdminView;
import ar.cetobhb.catalog.domain.ProductEditData;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.StaleProductVersionException;
import ar.cetobhb.checkout.service.StorefrontService;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BuyerFixtures;
import ar.cetobhb.support.StockReconciliation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ACAT-04, ACAT-05, CAT-A3, AUD-02: an edit is audited per field group (price, everything else), a no-op writes no
 * row and no version, a stale version changes nothing, and an activation change writes one row.
 */
class AdminCatalogUpdateIT extends AbstractPostgresIT {

    @Autowired
    private AdminCatalogService admin;

    @Autowired
    private AdminInventoryService adminInventory;

    @Autowired
    private AdminOrderService adminOrders;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private StorefrontService storefront;

    @Autowired
    private JdbcTemplate jdbc;

    private long id;

    @BeforeEach
    void setUp() {
        id = admin.createBase(product("Frasco", "100.00")).id();
        adminInventory.adjust(id, 20, StockAdjustmentReason.RESTOCK);
    }

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private static ProductEditData edit(String name, String price) {
        return new ProductEditData(name, new BigDecimal(price), null);
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id);
    }

    private List<String> actions() {
        return jdbc.queryForList("SELECT action FROM admin_audit_log WHERE entity_id = ? AND action <> 'STOCK_ADJUSTED'"
                + " AND action <> 'PRODUCT_CREATED' ORDER BY id", String.class, id);
    }

    private long order(int quantity) {
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), BuyerFixtures.freshBuyer(),
                List.of(new PlaceOrderCommand.Line(id, quantity)))).orderId();
    }

    @Test
    void aPriceChangeWritesOnePriceChangedRowWithBeforeAndAfter() {
        long before = version();

        ProductAdminView view = admin.update(id, before, edit("Frasco", "150.00"));

        assertThat(view.price()).isEqualByComparingTo("150.00");
        assertThat(view.version()).isGreaterThan(before);
        assertThat(actions()).containsExactly("PRICE_CHANGED");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_log WHERE action = 'PRICE_CHANGED'");
        assertThat((BigDecimal) row.get("before_value")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) row.get("after_value")).isEqualByComparingTo("150.00");
        assertThat(row.get("entity_id")).isEqualTo(id);
    }

    @Test
    void aNameOnlyChangeWritesProductUpdatedAndAChangeOfBothWritesBothRows() {
        admin.update(id, version(), edit("Frasco 60", "100.00"));
        assertThat(actions()).containsExactly("PRODUCT_UPDATED");

        admin.update(id, version(), edit("Frasco 60 caps", "120.00"));
        assertThat(actions()).containsExactly("PRODUCT_UPDATED", "PRICE_CHANGED", "PRODUCT_UPDATED");
    }

    @Test
    void anEditThatChangesNothingWritesNoRowAndDoesNotBumpTheVersion() {
        long before = version();

        ProductAdminView view = admin.update(id, before, edit("Frasco", "100.00"));

        assertThat(view.version()).isEqualTo(before);
        assertThat(version()).isEqualTo(before);
        assertThat(actions()).isEmpty();
    }

    @Test
    void aStaleVersionChangesNothingAndWritesNoRow() {
        long stale = version();
        admin.update(id, stale, edit("Frasco", "130.00"));
        int rows = actions().size();

        assertThatThrownBy(() -> admin.update(id, stale, edit("Otro", "999.00")))
                .isInstanceOf(StaleProductVersionException.class);

        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("130.00");
        assertThat(actions()).hasSize(rows);
        assertThatThrownBy(() -> admin.update(id + 1000, 0, edit("Otro", "10.00")))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void anAuditInsertThatFailsRollsTheEditBack() {
        long before = version();
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_audit_edit_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'audit refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER fail_audit_edit_for_test BEFORE INSERT ON admin_audit_log FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_audit_edit_for_test()");

            assertThatThrownBy(() -> admin.update(id, before, edit("Frasco", "150.00")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> admin.deactivate(id)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_audit_edit_for_test ON admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_audit_edit_for_test()");
        }

        assertThat(version()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void anActivationChangeWritesOneRowAndARepeatWritesNone() {
        assertThat(admin.activate(id)).as("already active").isFalse();
        assertThat(actions()).isEmpty();

        assertThat(admin.deactivate(id)).isTrue();
        assertThat(admin.deactivate(id)).isFalse();
        assertThat(actions()).containsExactly("PRODUCT_DEACTIVATED");
        assertThat(storefront.listProducts()).isEmpty();

        assertThat(admin.activate(id)).isTrue();
        assertThat(actions()).containsExactly("PRODUCT_DEACTIVATED", "PRODUCT_ACTIVATED");
        assertThat(storefront.listProducts()).extracting(p -> p.product().id()).containsExactly(id);
        assertThatThrownBy(() -> admin.deactivate(id + 1000)).isInstanceOf(ProductNotFoundException.class);
        assertThat(actions()).hasSize(2);
    }

    @Test
    void deactivatingAProductWithPendingOrdersKeepsThemPayableAndCancellable() {
        long toPay = order(3);
        long toCancel = order(2);

        admin.deactivate(id);

        assertThat(adminOrders.pay(toPay).status()).isEqualTo(OrderStatus.PAGADO);
        assertThat(adminOrders.cancel(toCancel).status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class, id))
                .isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void anOrderKeepsItsPriceAfterAnEditAndANewOrderUsesTheNewOne() {
        long first = order(2);

        admin.update(id, version(), edit("Frasco", "150.00"));
        long second = order(2);

        assertThat(unitPrice(first)).isEqualByComparingTo("100.00");
        assertThat(total(first)).isEqualByComparingTo("200.00");
        assertThat(unitPrice(second)).isEqualByComparingTo("150.00");
    }

    private BigDecimal unitPrice(long orderId) {
        return jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id = ?", BigDecimal.class, orderId);
    }

    private BigDecimal total(long orderId) {
        return jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, orderId);
    }
}
