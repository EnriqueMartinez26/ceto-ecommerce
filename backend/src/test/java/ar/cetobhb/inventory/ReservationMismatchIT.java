package ar.cetobhb.inventory;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.ReservationMismatch;
import ar.cetobhb.inventory.repository.InventoryStockRepository;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** AINV-06 / INV-A2: a single read-only statement that names the products whose reserved count has drifted. */
class ReservationMismatchIT extends AbstractPostgresIT {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private InventoryStockRepository stockRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private long baseWithStock(String name, int onHand) {
        long id = catalog.createBase(product(name, "10.00")).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private void order(long productId, int quantity) {
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(),
                List.of(new PlaceOrderCommand.Line(productId, quantity))));
    }

    @Test
    void consistentDataReturnsNothing() {
        long base = baseWithStock("Base", 10);
        order(base, 3);

        assertThat(inventory.reservationMismatches(100)).isEmpty();
    }

    @Test
    void aReservedCounterForcedOffReturnsExactlyThatProductWithBothFigures() {
        long drifting = baseWithStock("Drifting", 10);
        long healthy = baseWithStock("Healthy", 10);
        order(drifting, 2);
        order(healthy, 1);

        jdbc.update("UPDATE inventory_stock SET reserved = reserved + 1 WHERE product_id = ?", drifting);

        assertThat(inventory.reservationMismatches(100))
                .containsExactly(new ReservationMismatch(drifting, 3, 2));
    }

    @Test
    void reservedUnitsWithoutPendingLinesAreDrift() {
        long drifting = baseWithStock("Drifting", 10);
        jdbc.update("UPDATE inventory_stock SET reserved = 2 WHERE product_id = ?", drifting);

        assertThat(inventory.reservationMismatches(100))
                .containsExactly(new ReservationMismatch(drifting, 2, 0));
    }

    @Test
    void aPendingOrderWhoseStockRowWasDeletedHasANullReserved() {
        long orphan = baseWithStock("Orphan", 10);
        order(orphan, 2);
        jdbc.update("DELETE FROM inventory_stock WHERE product_id = ?", orphan);

        assertThat(inventory.reservationMismatches(100))
                .containsExactly(new ReservationMismatch(orphan, null, 2));
    }

    @Test
    void theLimitIsHonouredAndRowsComeInProductIdOrder() {
        long a = baseWithStock("A", 10);
        long b = baseWithStock("B", 10);
        long c = baseWithStock("C", 10);
        jdbc.update("UPDATE inventory_stock SET reserved = 1 WHERE product_id IN (?, ?, ?)", c, a, b);

        assertThat(inventory.reservationMismatches(2)).extracting(ReservationMismatch::productId)
                .containsExactly(a, b);
    }

    @Test
    void readsLeaveTheDataUntouched() {
        long drifting = baseWithStock("Drifting", 10);
        jdbc.update("UPDATE inventory_stock SET reserved = 5 WHERE product_id = ?", drifting);

        inventory.reservationMismatches(100);

        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class,
                drifting)).isEqualTo(5);
    }

    @Test
    void doesNotWaitForARowLockHeldByAnotherTransaction() throws Exception {
        long id = baseWithStock("Locked", 10);
        jdbc.update("UPDATE inventory_stock SET reserved = 1 WHERE product_id = ?", id);
        CompletableFuture<List<ReservationMismatch>> read = new CompletableFuture<>();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            stockRepository.lockByProductId(id).orElseThrow();
            CompletableFuture.runAsync(() -> read.complete(inventory.reservationMismatches(100)));
            try {
                read.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("the reconciliation waited for the row lock", e);
            }
        });

        assertThat(read.get()).containsExactly(new ReservationMismatch(id, 1, 0));
    }
}
