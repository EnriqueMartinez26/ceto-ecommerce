package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StockNotAdjustableException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The admin stock paths (adjust, stock view, reconciliation) never write buyer data or product names to the log, not
 * on success, not on a rejection and not when the audit insert fails and everything rolls back. The sentinels are
 * unique strings: if one shows up in the captured output, a path logged a name, a phone or an address.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminStockPiiLoggingIT extends AbstractPostgresIT {

    private static final String PRODUCT = "SentinelStockProductQ";
    private static final String NAME = "SentinelStockBuyerQ";
    private static final String PHONE = "+5493515557777";
    private static final String ADDRESS = "SentinelStockStreetQ 7777";

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

    @Test
    void everyStockPathLeavesNoBuyerDataOrProductNameInTheLog(CapturedOutput output) {
        long base = catalog.createBase(product(PRODUCT, "100.00")).id();
        inventory.createStock(base, 5);
        long combo = catalog.createCombo(product(PRODUCT + " combo", "250.00"), base, 2).id();
        long unconfigured = catalog.createBase(product(PRODUCT + " unconfigured", "10.00")).id();
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(NAME, PHONE, ADDRESS, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, 3))));

        admin.adjust(base, 10, StockAdjustmentReason.RESTOCK);
        assertThatThrownBy(() -> admin.adjust(base, -100, StockAdjustmentReason.OTHER))
                .isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> admin.adjust(combo, 1, StockAdjustmentReason.OTHER))
                .isInstanceOf(StockNotAdjustableException.class);
        assertThatThrownBy(() -> admin.adjust(unconfigured, 1, StockAdjustmentReason.OTHER))
                .isInstanceOf(StockNotAdjustableException.class);
        admin.stockView();
        jdbc.update("UPDATE inventory_stock SET reserved = 1 WHERE product_id = ?", base);
        assertThat(admin.reconciliation().rows()).hasSize(1);
        jdbc.update("UPDATE inventory_stock SET reserved = 3 WHERE product_id = ?", base);
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_audit_stock_pii_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'audit refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER fail_audit_stock_pii_for_test BEFORE INSERT ON admin_audit_log FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_audit_stock_pii_for_test()");
            assertThatThrownBy(() -> admin.adjust(base, 1, StockAdjustmentReason.COUNT_CORRECTION))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_audit_stock_pii_for_test ON admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_audit_stock_pii_for_test()");
        }

        assertThat(output.getAll())
                .doesNotContain(PRODUCT)
                .doesNotContain(NAME)
                .doesNotContain(PHONE)
                .doesNotContain("5557777")
                .doesNotContain(ADDRESS);
    }
}
