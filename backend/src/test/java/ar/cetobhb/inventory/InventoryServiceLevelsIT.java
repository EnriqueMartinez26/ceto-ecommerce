package ar.cetobhb.inventory;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.StockCounts;
import ar.cetobhb.inventory.repository.InventoryStock;
import ar.cetobhb.inventory.repository.InventoryStockRepository;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** AINV-01 / INV-A2 read model: stock counters per product, ordered, lock-free and without managed rows. */
class InventoryServiceLevelsIT extends AbstractPostgresIT {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private CatalogService catalog;

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
    void returnsTheCountersOrderedByProductId() {
        long first = baseWithStock("First", 10, 3);
        long second = baseWithStock("Second", 7, 0);
        long third = baseWithStock("Third", 4, 4);

        Map<Long, StockCounts> levels = inventory.levels(List.of(third, first, second));

        assertThat(new ArrayList<>(levels.keySet())).containsExactly(first, second, third);
        assertThat(levels).containsEntry(first, new StockCounts(10, 3))
                .containsEntry(second, new StockCounts(7, 0))
                .containsEntry(third, new StockCounts(4, 4));
    }

    @Test
    void aProductWithoutAStockRowAndAnUnknownIdAreAbsentKeys() {
        long configured = baseWithStock("Configured", 4, 1);
        long unconfigured = catalog.createBase(product("Unconfigured", "10.00")).id();

        assertThat(inventory.levels(Set.of(configured, unconfigured, 999_999L))).containsOnlyKeys(configured);
    }

    @Test
    void anEmptyRequestReadsNothing() {
        assertThat(inventory.levels(Set.of())).isEmpty();
    }

    @Test
    void doesNotWaitForARowLockHeldByAnotherTransaction() throws Exception {
        long id = baseWithStock("Locked", 6, 2);
        CompletableFuture<Map<Long, StockCounts>> read = new CompletableFuture<>();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            stockRepository.lockByProductId(id).orElseThrow();
            CompletableFuture.runAsync(() -> read.complete(inventory.levels(Set.of(id))));
            try {
                read.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("levels waited for the row lock", e);
            }
        });

        assertThat(read.get()).containsOnly(Map.entry(id, new StockCounts(6, 2)));
    }

    @Test
    void leavesNoManagedStockRowSoALaterLockQueryReadsTheCommittedValue() {
        long id = baseWithStock("Fresh", 10, 0);
        Integer reservedSeenByLock = new TransactionTemplate(txManager).execute(status -> {
            inventory.levels(Set.of(id));
            CompletableFuture.runAsync(
                    () -> jdbc.update("UPDATE inventory_stock SET reserved = 4 WHERE product_id = ?", id)).join();
            return stockRepository.lockByProductId(id).map(InventoryStock::reserved).orElseThrow();
        });

        assertThat(reservedSeenByLock).isEqualTo(4);
    }
}
