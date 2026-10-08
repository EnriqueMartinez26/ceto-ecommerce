package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.service.AdminInventoryService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.domain.ReservationMismatch;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BuyerFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** AINV-06 through the facade: clean data gives nothing, drift gives exactly the product, nothing is changed. */
class AdminReconciliationIT extends AbstractPostgresIT {

    @Autowired
    private AdminInventoryService admin;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JdbcTemplate jdbc;

    private long baseWithPendingOrder(String name, int quantity) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, 10);
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), BuyerFixtures.freshBuyer(),
                List.of(new PlaceOrderCommand.Line(id, quantity))));
        return id;
    }

    @Test
    void consistentDataGivesAnEmptyList() {
        baseWithPendingOrder("Frasco", 3);

        assertThat(admin.reconciliation().rows()).isEmpty();
    }

    @Test
    void aCorruptedReservedCounterIsTheOnlyMismatchAndNothingIsChanged() {
        long healthy = baseWithPendingOrder("Sano", 2);
        long corrupted = baseWithPendingOrder("Roto", 3);
        jdbc.update("UPDATE inventory_stock SET reserved = 5 WHERE product_id = ?", corrupted);

        assertThat(admin.reconciliation().rows()).containsExactly(new ReservationMismatch(corrupted, 5, 3));

        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class,
                corrupted)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class,
                healthy)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class)).isZero();
    }

    @Test
    void theLargestConfigurableCapPlusOneIsAcceptedByTheReadPath() {
        // AdminProperties allows up to 1000 rows and the facade asks for one more to detect truncation; a limit the
        // read path rejects would turn into a 500 at runtime.
        assertThat(inventory.reservationMismatches(1001)).isEmpty();
    }
}
