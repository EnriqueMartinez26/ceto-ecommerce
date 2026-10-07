package ar.cetobhb.inventory;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.StockReconciliation;
import ar.cetobhb.support.StockReconciliation.StockDrift;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The reconciliation helper must stay quiet on a consistent database and name exactly the drifting product. */
class StockReconciliationIT extends AbstractPostgresIT {

    private static final Buyer BUYER =
            new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);

    @Autowired
    private OrderPlacementService placement;

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

    @Test
    void pendingDemandForABaseProductWithoutAStockRowIsReported() {
        long orphan = catalog.createBase(product("Orphan", "10.00")).id();
        long healthy = catalog.createBase(product("Healthy", "10.00")).id();
        catalog.createBase(product("Never stocked", "10.00")).id();
        inventory.createStock(orphan, 5);
        inventory.createStock(healthy, 5);
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), BUYER,
                List.of(new PlaceOrderCommand.Line(orphan, 2), new PlaceOrderCommand.Line(healthy, 1))));
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();

        jdbc.update("DELETE FROM inventory_stock WHERE product_id = ?", orphan);

        // The stock-less product with pending units drifts (expected 2, no row = 0 reserved); a product that never
        // had a stock row nor pending lines does not.
        assertThat(StockReconciliation.stockDrift(jdbc))
                .containsExactly(new StockDrift(orphan, 2, 0));    }
}
