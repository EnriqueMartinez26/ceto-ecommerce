package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.domain.CappedList;
import ar.cetobhb.admin.domain.StockEntry;
import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.ReservationMismatch;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Review batch 4, item 1: the capped admin reads never truncate silently. Both caps are 2 here and the REAL queries run
 * (no mocks): with 3 rows the result carries the first 2 by id and {@code truncated = true}; with exactly 2 (cap equals
 * count) nothing is cut and {@code truncated = false}.
 */
@TestPropertySource(properties = {"ceto.admin.max-listed-products=2", "ceto.admin.max-reconciliation-rows=2"})
class AdminCappedReadsTruncationIT extends AbstractPostgresIT {

    @Autowired
    private AdminInventoryService admin;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private long baseWithStock(String name) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, 10);
        return id;
    }

    /** reserved = 1 with no pending order line is drift: one mismatch row for the product. */
    private long mismatchedBase(String name) {
        long id = baseWithStock(name);
        jdbc.update("UPDATE inventory_stock SET reserved = 1 WHERE product_id = ?", id);
        return id;
    }

    @Test
    void theStockViewOfThreeProductsAtCapTwoIsTruncatedAndHoldsTheFirstTwoById() {
        long first = baseWithStock("Uno");
        long second = baseWithStock("Dos");
        baseWithStock("Tres");

        CappedList<StockEntry> view = admin.stockView();

        assertThat(view.truncated()).isTrue();
        assertThat(view.rows()).extracting(StockEntry::productId).containsExactly(first, second);
    }

    @Test
    void theStockViewWhoseCountEqualsTheCapIsNotTruncated() {
        long first = baseWithStock("Uno");
        long second = baseWithStock("Dos");

        CappedList<StockEntry> view = admin.stockView();

        assertThat(view.truncated()).isFalse();
        assertThat(view.rows()).extracting(StockEntry::productId).containsExactly(first, second);
    }

    @Test
    void theReconciliationOfThreeMismatchesAtCapTwoIsTruncatedAndHoldsTheFirstTwoById() {
        long first = mismatchedBase("Uno");
        long second = mismatchedBase("Dos");
        mismatchedBase("Tres");

        CappedList<ReservationMismatch> report = admin.reconciliation();

        assertThat(report.truncated()).isTrue();
        assertThat(report.rows()).containsExactly(new ReservationMismatch(first, 1, 0),
                new ReservationMismatch(second, 1, 0));
    }

    @Test
    void theReconciliationWhoseCountEqualsTheCapIsNotTruncated() {
        long first = mismatchedBase("Uno");
        long second = mismatchedBase("Dos");

        CappedList<ReservationMismatch> report = admin.reconciliation();

        assertThat(report.truncated()).isFalse();
        assertThat(report.rows()).containsExactly(new ReservationMismatch(first, 1, 0),
                new ReservationMismatch(second, 1, 0));
    }
}
