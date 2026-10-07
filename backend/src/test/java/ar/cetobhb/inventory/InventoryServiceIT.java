package ar.cetobhb.inventory;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StockNotConfiguredException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real commits, no @Transactional on the test: the service joins a transaction the test owns. */
class InventoryServiceIT extends AbstractPostgresIT {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private long baseWithStock(int onHand) {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        inventory.createStock(base.id(), onHand);
        return base.id();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT on_hand, reserved FROM inventory_stock WHERE product_id = ?", id);
    }

    private void assertCounts(long id, int onHand, int reserved) {
        assertThat(row(id)).containsEntry("on_hand", onHand).containsEntry("reserved", reserved);
    }

    private BaseUnitDemand demand(long... idsAndUnits) {
        BaseUnitDemand d = new BaseUnitDemand();
        for (int i = 0; i < idsAndUnits.length; i += 2) {
            d.addLine(idsAndUnits[i], 1, (int) idsAndUnits[i + 1]);
        }
        return d;
    }

    private void inTx(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }

    @Test
    void createStockIsRefusedForAComboAndForAMissingProduct() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        ProductSnapshot combo = catalog.createCombo(product("Combo", "270.00"), base.id(), 3);

        assertThatThrownBy(() -> inventory.createStock(combo.id(), 5))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_COMBO_LINK));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_stock", Long.class)).isZero();
        assertThatThrownBy(() -> inventory.createStock(9999L, 5))
                .isInstanceOf(ar.cetobhb.catalog.domain.ProductNotAvailableException.class);
    }

    @Test
    void createStockTwiceIsRefused() {
        long id = baseWithStock(5);
        assertThatThrownBy(() -> inventory.createStock(id, 7)).isInstanceOf(IllegalStateException.class);
        assertCounts(id, 5, 0);
    }

    @Test
    void missingStockRowFailsClosed() {
        ProductSnapshot noStock = catalog.createBase(product("No stock", "10.00"));

        assertThatThrownBy(() -> inTx(() -> inventory.reserve(demand(noStock.id(), 1))))
                .isInstanceOfSatisfying(StockNotConfiguredException.class,
                        e -> assertThat(e.productId()).isEqualTo(noStock.id()));
    }

    @Test
    void reserveAddsToReserved() {
        long id = baseWithStock(5);
        inTx(() -> inventory.reserve(demand(id, 2)));
        assertCounts(id, 5, 2);
    }

    @Test
    void reserveIsAllOrNothingAndNamesTheShortProduct() {
        long a = baseWithStock(5);
        long b = baseWithStock(1);

        assertThatThrownBy(() -> inTx(() -> inventory.reserve(demand(a, 2, b, 2))))
                .isInstanceOfSatisfying(InsufficientStockException.class,
                        e -> assertThat(e.productIds()).containsExactly(b));

        assertCounts(a, 5, 0);
        assertCounts(b, 1, 0);
    }

    @Test
    void shortageSurfacesAsDomainExceptionBeforeTheDatabaseCheckFires() {
        long id = baseWithStock(3);
        assertThatThrownBy(() -> inTx(() -> inventory.reserve(demand(id, 4))))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void releaseCommitSaleAndRestockApplyTheirExactEffects() {
        long id = baseWithStock(5);
        inTx(() -> inventory.reserve(demand(id, 3)));

        inTx(() -> inventory.release(demand(id, 1)));
        assertCounts(id, 5, 2);

        inTx(() -> inventory.commitSale(demand(id, 2)));
        assertCounts(id, 3, 0);

        inTx(() -> inventory.restock(demand(id, 2)));
        assertCounts(id, 5, 0);
    }

    @Test
    void releaseOrCommitBeyondReservedIsRefusedAndChangesNothing() {
        long id = baseWithStock(5);
        inTx(() -> inventory.reserve(demand(id, 1)));

        assertThatThrownBy(() -> inTx(() -> inventory.release(demand(id, 2))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inTx(() -> inventory.commitSale(demand(id, 2))))
                .isInstanceOf(IllegalStateException.class);
        assertCounts(id, 5, 1);
    }

    @Test
    void effectsRollBackWithTheCallersTransaction() {
        long id = baseWithStock(5);
        assertThatThrownBy(() -> inTx(() -> {
            inventory.reserve(demand(id, 2));
            throw new IllegalStateException("caller fails after reserving");
        })).isInstanceOf(IllegalStateException.class);
        assertCounts(id, 5, 0);
    }

    @Test
    void stockOperationsRequireTheCallersTransaction() {
        long id = baseWithStock(5);
        BaseUnitDemand d = demand(id, 1);

        assertThatThrownBy(() -> inventory.reserve(d)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inventory.release(d)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inventory.commitSale(d)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inventory.restock(d)).isInstanceOf(IllegalTransactionStateException.class);
        assertCounts(id, 5, 0);
    }

    @Test
    void adjustChangesOnHandButNeverBelowReserved() {
        long id = baseWithStock(5);
        inTx(() -> inventory.reserve(demand(id, 3)));

        inventory.adjust(id, 10);
        assertCounts(id, 10, 3);

        assertThatThrownBy(() -> inventory.adjust(id, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inventory.adjust(id, -1)).isInstanceOf(IllegalArgumentException.class);
        assertCounts(id, 10, 3);
        assertThatThrownBy(() -> inventory.adjust(9999L, 1)).isInstanceOf(StockNotConfiguredException.class);
    }

    @Test
    void multiProductDemandIsAppliedInOneTransaction() {
        long a = baseWithStock(5);
        long b = baseWithStock(5);
        inTx(() -> inventory.reserve(demand(b, 1, a, 2)));
        assertThat(List.of(row(a).get("reserved"), row(b).get("reserved"))).containsExactly(2, 1);
    }
}
