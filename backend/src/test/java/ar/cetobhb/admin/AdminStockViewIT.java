package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.domain.StockEntry;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.repository.InventoryStock;
import ar.cetobhb.inventory.repository.InventoryStockRepository;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** AINV-01: the stock view lists every product by id, derives combo availability and never fails on a missing row. */
class AdminStockViewIT extends AbstractPostgresIT {

    @Autowired
    private AdminInventoryService admin;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private InventoryStockRepository stockRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private long baseWithStock(String name, int onHand, int reserved) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, onHand);
        jdbc.update("UPDATE inventory_stock SET reserved = ? WHERE product_id = ?", reserved, id);
        return id;
    }

    @Test
    void aBaseShowsOnHandReservedAndAvailableAndAComboDerivesFromItsBase() {
        long base = baseWithStock("Frasco", 10, 3);
        long combo = catalog.createCombo(product("Combo x2", "180.00"), base, 2).id();

        List<StockEntry> view = admin.stockView();

        assertThat(view).containsExactly(
                new StockEntry(base, "Frasco", true, null, null, true, 10, 3, 7L),
                new StockEntry(combo, "Combo x2", true, base, 2, true, null, null, 3L));
    }

    @Test
    void aBaseWithoutAStockRowIsListedAsNotConfiguredWithNoFigures() {
        long unconfigured = catalog.createBase(product("Sin stock", "10.00")).id();
        long combo = catalog.createCombo(product("Combo", "25.00"), unconfigured, 3).id();

        assertThat(admin.stockView()).containsExactly(
                new StockEntry(unconfigured, "Sin stock", true, null, null, false, null, null, null),
                new StockEntry(combo, "Combo", true, unconfigured, 3, false, null, null, null));
    }

    @Test
    void inactiveProductsAreListedFlaggedAndTheOrderIsByIdNotByName() {
        long zeta = baseWithStock("Zeta", 4, 4);
        long alfa = baseWithStock("Alfa", 6, 1);
        catalog.setActive(alfa, false);

        List<StockEntry> view = admin.stockView();

        assertThat(view).extracting(StockEntry::productId).containsExactly(zeta, alfa);
        assertThat(view.get(0).available()).as("fully reserved is zero free, never negative").isZero();
        assertThat(view.get(1).active()).isFalse();
        assertThat(view.get(1).available()).isEqualTo(5L);
    }

    @Test
    void doesNotWaitForARowLockHeldByAnotherTransaction() throws Exception {
        long id = baseWithStock("Locked", 6, 2);
        CompletableFuture<List<StockEntry>> read = new CompletableFuture<>();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            stockRepository.lockByProductId(id).orElseThrow();
            CompletableFuture.runAsync(() -> read.complete(admin.stockView()));
            try {
                read.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("the stock view waited for the row lock", e);
            }
        });

        assertThat(read.get()).extracting(StockEntry::onHand).containsExactly(6);
    }

    @Test
    void leavesNoManagedStockRowSoALaterLockQueryReadsTheCommittedValue() {
        long id = baseWithStock("Fresh", 10, 0);
        Integer reservedSeenByLock = new TransactionTemplate(txManager).execute(status -> {
            admin.stockView();
            CompletableFuture.runAsync(
                    () -> jdbc.update("UPDATE inventory_stock SET reserved = 4 WHERE product_id = ?", id)).join();
            return stockRepository.lockByProductId(id).map(InventoryStock::reserved).orElseThrow();
        });

        assertThat(reservedSeenByLock).isEqualTo(4);
    }
}
