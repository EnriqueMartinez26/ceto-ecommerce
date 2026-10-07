package ar.cetobhb.inventory;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.StockReconciliation;
import ar.cetobhb.support.StockReconciliation.StockDrift;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The reconciliation helper must stay quiet on a consistent database and name exactly the drifting product. */
class StockReconciliationIT extends AbstractPostgresIT {

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anEmptyAndAConsistentDatabaseHaveNoDrift() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();

        long base = catalog.createBase(product("Base", "10.00")).id();
        inventory.createStock(base, 5);

        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    @Test
    void reservedWithoutPendingLinesIsReportedForExactlyThatProduct() {
        long drifting = catalog.createBase(product("Drifting", "10.00")).id();
        long healthy = catalog.createBase(product("Healthy", "10.00")).id();
        inventory.createStock(drifting, 5);
        inventory.createStock(healthy, 5);

        jdbc.update("UPDATE inventory_stock SET reserved = reserved + 1 WHERE product_id = ?", drifting);

        assertThat(StockReconciliation.stockDrift(jdbc))
                .containsExactly(new StockDrift(drifting, 0, 1));
    }
}
