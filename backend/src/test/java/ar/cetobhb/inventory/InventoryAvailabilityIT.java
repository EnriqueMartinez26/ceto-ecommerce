package ar.cetobhb.inventory;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.repository.InventoryStock;
import ar.cetobhb.inventory.repository.InventoryStockRepository;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractPostgresIT;
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

/** The storefront read of free base units: no locks, no managed stock rows, a missing row is a missing key. */
class InventoryAvailabilityIT extends AbstractPostgresIT {

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

    private long baseWithStock(String name, int onHand) {
        ProductSnapshot base = catalog.createBase(product(name, "100.00"));
        inventory.createStock(base.id(), onHand);
        return base.id();
    }

    private void reserve(long baseId, int units) {
        BaseUnitDemand demand = new BaseUnitDemand();
        demand.addLine(baseId, 1, units);
        new TransactionTemplate(txManager).executeWithoutResult(status -> inventory.reserve(demand));
    }

    @Test
    void returnsFreeUnitsPerBaseProduct() {
        long plenty = baseWithStock("Plenty", 10);
        long taken = baseWithStock("Taken", 5);
        reserve(taken, 5);
        long partly = baseWithStock("Partly", 8);
        reserve(partly, 3);

        Map<Long, Long> free = inventory.availableByBase(List.of(plenty, taken, partly));

        assertThat(free).containsOnly(Map.entry(plenty, 10L), Map.entry(taken, 0L), Map.entry(partly, 5L));
    }

    @Test
    void aMissingStockRowIsAMissingKeyNotAnError() {
        long configured = baseWithStock("Configured", 4);
        long unconfigured = catalog.createBase(product("Unconfigured", "10.00")).id();

        assertThat(inventory.availableByBase(Set.of(configured, unconfigured, 999_999L)))
                .containsOnlyKeys(configured);
    }

    @Test
    void anEmptyRequestReadsNothing() {
        assertThat(inventory.availableByBase(Set.of())).isEmpty();
    }

    @Test
    void doesNotWaitForARowLockHeldByAnotherTransaction() throws Exception {
        long id = baseWithStock("Locked", 6);
        CompletableFuture<Map<Long, Long>> read = new CompletableFuture<>();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            stockRepository.lockByProductId(id).orElseThrow();
            CompletableFuture.runAsync(() -> read.complete(inventory.availableByBase(Set.of(id))));
            try {
                // Fails with a TimeoutException when the read queued behind the FOR UPDATE.
                read.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("availableByBase waited for the row lock", e);
            }
        });

        assertThat(read.get()).containsOnly(Map.entry(id, 6L));
    }

    @Test
    void leavesNoManagedStockRowSoALaterLockQueryReadsTheCommittedValue() throws Exception {
        long id = baseWithStock("Fresh", 10);
        Integer reservedSeenByLock = new TransactionTemplate(txManager).execute(status -> {
            inventory.availableByBase(Set.of(id));
            CompletableFuture.runAsync(
                    () -> jdbc.update("UPDATE inventory_stock SET reserved = 4 WHERE product_id = ?", id)).join();
            return stockRepository.lockByProductId(id).map(InventoryStock::reserved).orElseThrow();
        });

        assertThat(reservedSeenByLock).isEqualTo(4);
    }
}
