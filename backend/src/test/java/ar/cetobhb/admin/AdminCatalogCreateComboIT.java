package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.service.AdminCatalogService;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.checkout.domain.StockStatus;
import ar.cetobhb.checkout.service.StorefrontService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** ACAT-03: a combo is a priced link to a base product; it has no stock row and its availability is derived. */
class AdminCatalogCreateComboIT extends AbstractPostgresIT {

    @Autowired
    private AdminCatalogService admin;

    @Autowired
    private AdminInventoryService adminInventory;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private StorefrontService storefront;

    @Autowired
    private JdbcTemplate jdbc;

    private long base;

    @BeforeEach
    void setUp() {
        base = admin.createBase(product("Frasco", "100.00")).id();
        adminInventory.adjust(base, 10, StockAdjustmentReason.RESTOCK);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void aComboOverABaseDerivesItsAvailabilityCreatesNoStockRowAndWritesOneAuditRow() {
        int stockRows = count("inventory_stock");

        ProductSnapshot combo = admin.createCombo(product("Combo x3", "270.00"), base, 3);

        assertThat(combo.isCombo()).isTrue();
        assertThat(combo.comboBaseProductId()).isEqualTo(base);
        assertThat(combo.comboBaseUnits()).isEqualTo(3);
        assertThat(count("inventory_stock")).isEqualTo(stockRows);
        assertThat(adminInventory.stockView().rows()).filteredOn(e -> e.productId() == combo.id())
                .singleElement().satisfies(entry -> assertThat(entry.available()).isEqualTo(3L));
        assertThat(storefront.product(combo.id()).availability()).isEqualTo(StockStatus.AVAILABLE);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_log WHERE entity_id = ?"
                + " AND action = 'PRODUCT_CREATED'", combo.id());
        assertThat(row.get("entity_type")).isEqualTo("PRODUCT");
        assertThat(count("admin_audit_log")).as("the base creation, the restock and the combo").isEqualTo(3);
    }

    @Test
    void anInvalidPairPersistsNothing() {
        long combo = catalog.createCombo(product("Combo", "180.00"), base, 2).id();
        long inactive = catalog.createBase(product("Inactivo", "10.00")).id();
        catalog.setActive(inactive, false);
        int products = count("products");
        int auditRows = count("admin_audit_log");

        assertInvalidLink(() -> admin.createCombo(product("N1", "100.00"), base, 1));
        assertInvalidLink(() -> admin.createCombo(product("N101", "100.00"), base, 101));
        assertInvalidLink(() -> admin.createCombo(product("Sin base", "100.00"), base + 1000, 2));
        assertInvalidLink(() -> admin.createCombo(product("Combo de combo", "100.00"), combo, 2));
        assertInvalidLink(() -> admin.createCombo(product("Base inactiva", "100.00"), inactive, 2));
        assertThatThrownBy(() -> admin.createCombo(product("Precio", "0"), base, 2))
                .isInstanceOf(ValidationException.class);

        assertThat(count("products")).isEqualTo(products);
        assertThat(count("admin_audit_log")).isEqualTo(auditRows);
    }

    private static void assertInvalidLink(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_COMBO_LINK));
    }
}
