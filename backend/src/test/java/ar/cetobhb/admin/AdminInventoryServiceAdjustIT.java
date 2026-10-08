package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.domain.AdjustedStock;
import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StockNotAdjustableException;
import ar.cetobhb.inventory.service.InventoryService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The admin stock adjustment (AINV-02, AINV-03, AINV-04, AUD-02, AUD-03): one transaction that moves {@code on_hand},
 * writes exactly one STOCK_ADJUSTED row with the counts before and after and the closed-set reason (no note), and
 * leaves no row at all when anything fails.
 */
class AdminInventoryServiceAdjustIT extends AbstractPostgresIT {

    @Autowired
    private AdminInventoryService admin;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private long base;

    @BeforeEach
    void setUp() {
        base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 5);
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), BuyerFixtures.freshBuyer(),
                List.of(new PlaceOrderCommand.Line(base, 2))));
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class, base);
    }

    private int auditRows() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
    }

    private void assertUntouched() {
        assertThat(onHand()).isEqualTo(5);
        assertThat(reserved()).isEqualTo(2);
        assertThat(auditRows()).isZero();
    }

    @Test
    void aRestockMovesOnHandAndWritesOneRowWithBeforeAndAfter() {
        clock.set(MutableClock.START.plus(Duration.ofMinutes(9)));

        AdjustedStock result = admin.adjust(base, 20, StockAdjustmentReason.RESTOCK);

        assertThat(result).isEqualTo(new AdjustedStock(base, 25, 2, 23));
        assertThat(onHand()).isEqualTo(25);
        assertThat(reserved()).isEqualTo(2);
        assertThat(auditRows()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_log");
        assertThat(row.get("action")).isEqualTo("STOCK_ADJUSTED");
        assertThat(row.get("entity_type")).isEqualTo("PRODUCT");
        assertThat(row.get("entity_id")).isEqualTo(base);
        assertThat(row.get("reason")).isEqualTo("RESTOCK");
        assertThat((BigDecimal) row.get("before_value")).isEqualByComparingTo("5");
        assertThat((BigDecimal) row.get("after_value")).isEqualByComparingTo("25");
        assertThat(((Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void theAuditTableHasNoNoteOrTextColumnBeyondTheClosedSets() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'admin_audit_log' ORDER BY column_name", String.class))
                .containsExactlyInAnyOrder("id", "occurred_at", "action", "entity_type", "entity_id", "reason",
                        "before_value", "after_value");
    }

    @Test
    void anAdjustmentDownToExactlyReservedIsAllowedAndBelowItIsRejectedWithNoRow() {
        assertThatThrownBy(() -> admin.adjust(base, -4, StockAdjustmentReason.DAMAGED_OR_LOST))
                .isInstanceOf(InsufficientStockException.class);
        assertUntouched();

        AdjustedStock result = admin.adjust(base, -3, StockAdjustmentReason.DAMAGED_OR_LOST);

        assertThat(result).isEqualTo(new AdjustedStock(base, 2, 2, 0));
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    void aComboAProductWithoutAStockRowAndAnUnknownProductAreRejectedWithNoRow() {
        long combo = catalog.createCombo(product("Combo", "250.00"), base, 3).id();
        long unconfigured = catalog.createBase(product("Sin stock", "10.00")).id();

        assertThatThrownBy(() -> admin.adjust(combo, 1, StockAdjustmentReason.RESTOCK))
                .isInstanceOf(StockNotAdjustableException.class);
        assertThatThrownBy(() -> admin.adjust(unconfigured, 1, StockAdjustmentReason.RESTOCK))
                .isInstanceOf(StockNotAdjustableException.class);
        assertThatThrownBy(() -> admin.adjust(base + 1000, 1, StockAdjustmentReason.RESTOCK))
                .isInstanceOf(ProductNotFoundException.class);

        assertUntouched();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_stock", Integer.class)).isEqualTo(1);
    }

    @Test
    void aDeltaOfZeroOrBeyondTheBoundIsRejectedWithNoRow() {
        for (int delta : new int[] {0, 10_001, -10_001, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> admin.adjust(base, delta, StockAdjustmentReason.OTHER))
                    .as("delta %d", delta)
                    .isInstanceOfSatisfying(ValidationException.class,
                            e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_STOCK_DELTA));
        }
        assertThatThrownBy(() -> admin.adjust(base, 1, null)).isInstanceOf(NullPointerException.class);
        assertUntouched();
        assertThat(admin.adjust(base, 10_000, StockAdjustmentReason.OTHER).onHand()).isEqualTo(10_005);
    }

    @Test
    void anAuditInsertThatFailsRollsTheStockChangeBack() {
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_audit_stock_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'audit refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER fail_audit_stock_for_test BEFORE INSERT ON admin_audit_log FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_audit_stock_for_test()");

            assertThatThrownBy(() -> admin.adjust(base, 20, StockAdjustmentReason.RESTOCK))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_audit_stock_for_test ON admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_audit_stock_for_test()");
        }

        assertUntouched();
    }
}
