package ar.cetobhb.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.service.AdminCatalogService;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductEditData;
import ar.cetobhb.catalog.domain.StaleProductVersionException;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.math.BigDecimal;
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
 * The admin catalog paths (create, edit, activate, list) never write buyer data or product names to the log: not on
 * success, not on a rejection (invalid input, stale version) and not when a failing insert rolls everything back. The
 * sentinels are unique strings: if one shows up in the captured output, a path logged a name, a phone or an address.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminCatalogPiiLoggingIT extends AbstractPostgresIT {

    private static final String PRODUCT = "SentinelCatalogProductQ";
    private static final String NAME = "SentinelCatalogBuyerQ";
    private static final String PHONE = "+5493515556666";
    private static final String ADDRESS = "SentinelCatalogStreetQ 6666";

    @Autowired
    private AdminCatalogService admin;

    @Autowired
    private AdminInventoryService adminInventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JdbcTemplate jdbc;

    private static NewProduct data(String name, String price) {
        return new NewProduct(name, new BigDecimal(price), null, null, null, null);
    }

    @Test
    void everyCatalogPathLeavesNoBuyerDataOrProductNameInTheLog(CapturedOutput output) {
        long base = admin.createBase(data(PRODUCT, "100.00")).id();
        adminInventory.adjust(base, 10, StockAdjustmentReason.RESTOCK);
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(NAME, PHONE, ADDRESS, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, 1))));
        admin.createCombo(data(PRODUCT + " combo", "250.00"), base, 2);

        long version = admin.list().get(0).version();
        admin.update(base, version, new ProductEditData(PRODUCT + " 2", new BigDecimal("120.00"), null));
        assertThatThrownBy(() -> admin.update(base, version, new ProductEditData(PRODUCT, new BigDecimal("1.00"), null)))
                .isInstanceOf(StaleProductVersionException.class);
        assertThatThrownBy(() -> admin.createBase(data(PRODUCT + "\u0007", "100.00")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> admin.createCombo(data(PRODUCT + " bad", "100.00"), base, 1))
                .isInstanceOf(ValidationException.class);
        admin.deactivate(base);
        admin.activate(base);
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_audit_catalog_pii_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'audit refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER fail_audit_catalog_pii_for_test BEFORE INSERT ON admin_audit_log"
                    + " FOR EACH ROW EXECUTE FUNCTION fail_audit_catalog_pii_for_test()");
            assertThatThrownBy(() -> admin.createBase(data(PRODUCT + " fail", "100.00")))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_audit_catalog_pii_for_test ON admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_audit_catalog_pii_for_test()");
        }

        assertThat(output.getAll())
                .doesNotContain(PRODUCT)
                .doesNotContain(NAME)
                .doesNotContain(PHONE)
                .doesNotContain("5556666")
                .doesNotContain(ADDRESS);
    }
}
