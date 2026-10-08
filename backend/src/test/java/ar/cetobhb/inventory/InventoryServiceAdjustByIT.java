package ar.cetobhb.inventory;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StockAdjustment;
import ar.cetobhb.inventory.domain.StockNotAdjustableException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** INV-A1 / AINV-02..04 at service level: a relative, locked, validated stock change on a real Postgres. */
class InventoryServiceAdjustByIT extends AbstractPostgresIT {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    /** A base product with a stock row of (onHand, reserved); reserved is set by SQL, as a pending order would. */
    private long baseWithStock(int onHand, int reserved) {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        inventory.createStock(base.id(), onHand);
        jdbc.update("UPDATE inventory_stock SET reserved = ? WHERE product_id = ?", reserved, base.id());
        return base.id();
    }

    private void assertCounts(long id, int onHand, int reserved) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT on_hand, reserved FROM inventory_stock WHERE product_id = ?", id);
        assertThat(row).containsEntry("on_hand", onHand).containsEntry("reserved", reserved);
    }

    private int stockRows() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_stock", Integer.class);
    }

    @Test
    void aRestockReturnsTheBeforeAfterAndReservedCounts() {
        long id = baseWithStock(5, 2);

        StockAdjustment result = inventory.adjustBy(id, 20);

        assertThat(result).isEqualTo(new StockAdjustment(id, 5, 25, 2));
        assertCounts(id, 25, 2);
    }

    @Test
    void aDeltaBelowReservedIsRejectedAndTheRowIsUnchanged() {
        long id = baseWithStock(5, 2);

        assertThatThrownBy(() -> inventory.adjustBy(id, -4)).isInstanceOf(InsufficientStockException.class);

        assertCounts(id, 5, 2);
    }

    @Test
    void aDeltaDownExactlyToReservedSucceeds() {
        long id = baseWithStock(5, 2);

        assertThat(inventory.adjustBy(id, -3)).isEqualTo(new StockAdjustment(id, 5, 2, 2));

        assertCounts(id, 2, 2);
    }

    @Test
    void aNegativeResultIsRejected() {
        long id = baseWithStock(5, 0);

        assertThatThrownBy(() -> inventory.adjustBy(id, -6)).isInstanceOf(InsufficientStockException.class);

        assertCounts(id, 5, 0);
    }

    @Test
    void aZeroDeltaAndAnOutOfRangeResultAreValidationErrors() {
        long id = baseWithStock(5, 0);

        assertThatThrownBy(() -> inventory.adjustBy(id, 0)).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_STOCK_DELTA));
        assertThatThrownBy(() -> inventory.adjustBy(id, Integer.MAX_VALUE)).isInstanceOfSatisfying(
                ValidationException.class, e -> assertThat(e.reason()).isEqualTo(ReasonCode.STOCK_OUT_OF_RANGE));

        assertCounts(id, 5, 0);
    }

    @Test
    void anUnknownProductIsNotFound() {
        assertThatThrownBy(() -> inventory.adjustBy(9999L, 5)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void aComboHasNoStockOfItsOwnAndIsNotAdjustable() {
        long baseId = baseWithStock(10, 0);
        ProductSnapshot combo = catalog.createCombo(product("Combo", "270.00"), baseId, 3);

        assertThatThrownBy(() -> inventory.adjustBy(combo.id(), 5)).isInstanceOf(StockNotAdjustableException.class);

        assertThat(stockRows()).isEqualTo(1);
        assertCounts(baseId, 10, 0);
    }

    @Test
    void aProductWithoutAStockRowIsNotAdjustableAndNoRowIsCreated() {
        ProductSnapshot base = catalog.createBase(product("No stock yet", "100.00"));

        assertThatThrownBy(() -> inventory.adjustBy(base.id(), 5)).isInstanceOf(StockNotAdjustableException.class);

        assertThat(stockRows()).isZero();
    }

    @Test
    void aDeactivatedProductCanStillBeCorrected() {
        long id = baseWithStock(5, 0);
        catalog.setActive(id, false);

        assertThat(inventory.adjustBy(id, 1)).isEqualTo(new StockAdjustment(id, 5, 6, 0));
    }
}
