package ar.cetobhb.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.service.AdminCatalogService;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.checkout.domain.StockStatus;
import ar.cetobhb.checkout.service.StorefrontService;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BuyerFixtures;
import ar.cetobhb.support.MutableClock;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ACAT-02 / CAT-A2 / AUD-02 / AUD-03: a base product is created together with its zero stock row and one
 * PRODUCT_CREATED audit row in ONE transaction, so the storefront never sees a configured-less product.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminCatalogCreateBaseIT extends AbstractPostgresIT {

    @Autowired
    private AdminCatalogService admin;

    @Autowired
    private AdminInventoryService adminInventory;

    @Autowired
    private StorefrontService storefront;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private static NewProduct data(String name, String price, String compareAt) {
        return new NewProduct(name, new BigDecimal(price), compareAt == null ? null : new BigDecimal(compareAt),
                null, null, null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void createsTheProductItsZeroStockRowAndOneAuditRowTogether() {
        clock.set(MutableClock.START.plus(Duration.ofMinutes(4)));

        ProductSnapshot created = admin.createBase(data("Frasco", "100.00", "120.00"));

        assertThat(created.name()).isEqualTo("Frasco");
        assertThat(created.isCombo()).isFalse();
        Map<String, Object> stock = jdbc.queryForMap("SELECT * FROM inventory_stock WHERE product_id = ?",
                created.id());
        assertThat(stock.get("on_hand")).isEqualTo(0);
        assertThat(stock.get("reserved")).isEqualTo(0);
        assertThat(count("admin_audit_log")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_log");
        assertThat(row.get("action")).isEqualTo("PRODUCT_CREATED");
        assertThat(row.get("entity_type")).isEqualTo("PRODUCT");
        assertThat(row.get("entity_id")).isEqualTo(created.id());
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("before_value")).isNull();
        assertThat(row.get("after_value")).isNull();
        assertThat(((Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void aFreshProductIsSoldOutWithoutAnErrorThenAvailableAfterARestock(CapturedOutput output) {
        long id = admin.createBase(data("Frasco", "100.00", null)).id();

        assertThat(storefront.product(id).availability()).isEqualTo(StockStatus.SOLD_OUT);
        assertThatThrownBy(() -> placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                BuyerFixtures.freshBuyer(), List.of(new PlaceOrderCommand.Line(id, 1)))))
                .as("a configured zero row is a plain shortage, not a missing-stock error")
                .isInstanceOf(InsufficientStockException.class);
        assertThat(output.getAll()).doesNotContain("ERROR");

        adminInventory.adjust(id, 5, 0, StockAdjustmentReason.RESTOCK);

        assertThat(storefront.product(id).availability()).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void aFailingStockInsertLeavesNoProductAndNoAuditRow() {
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_stock_insert_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'stock refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER fail_stock_insert_for_test BEFORE INSERT ON inventory_stock FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_stock_insert_for_test()");

            assertThatThrownBy(() -> admin.createBase(data("Frasco", "100.00", null)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_stock_insert_for_test ON inventory_stock");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_stock_insert_for_test()");
        }

        assertThat(count("products")).isZero();
        assertThat(count("inventory_stock")).isZero();
        assertThat(count("admin_audit_log")).isZero();
    }

    @Test
    void invalidInputPersistsNothing() {
        List<NewProduct> invalid = List.of(
                data("Frasco", "0", null), data("Frasco", "-1", null), data("Frasco", "1000000000000", null),
                data("Frasco", "10.123", null), data("Frasco", "100.00", "100.00"), data("Frasco", "100.00", "99.99"),
                data("x".repeat(201), "100.00", null), data("Fras\u0007co", "100.00", null), data("  ", "100.00", null));

        for (NewProduct product : invalid) {
            assertThatThrownBy(() -> admin.createBase(product)).as("%s", product)
                    .isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> admin.createBase(null)).isInstanceOf(ValidationException.class);

        assertThat(count("products")).isZero();
        assertThat(count("inventory_stock")).isZero();
        assertThat(count("admin_audit_log")).isZero();
    }
}
