package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A product whose base has no stock row is a data problem for the operator, not for the buyer (REQ-ERR-04): the buyer
 * path reports the REQUESTED ids as unavailable, never the combo's base id, and the operator gets one ERROR line.
 */
@ExtendWith(OutputCaptureExtension.class)
class StockNotConfiguredPlacementIT extends AbstractPostgresIT {

    private static final String PHONE = "+5493515559876";
    private static final Buyer BUYER = new Buyer("Sentinel Name", PHONE, "Sentinel Street 9", ShippingZone.RESTO_DEL_PAIS);

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private PlaceOrderCommand order(long... productIds) {
        List<PlaceOrderCommand.Line> lines = java.util.Arrays.stream(productIds)
                .mapToObj(id -> new PlaceOrderCommand.Line(id, 1)).toList();
        return new PlaceOrderCommand(UUID.randomUUID().toString(), BUYER, lines);
    }

    @Test
    void aBaseWithoutStockRowIsReportedAsUnavailableWithItsOwnId(CapturedOutput output) {
        long healthy = catalog.createBase(product("Healthy", "10.00")).id();
        inventory.createStock(healthy, 5);
        long unconfigured = catalog.createBase(product("No stock row", "10.00")).id();

        assertThatThrownBy(() -> placement.placeOrder(order(healthy, unconfigured)))
                .isInstanceOfSatisfying(ProductNotAvailableException.class,
                        e -> assertThat(e.productIds()).containsExactlyInAnyOrder(healthy, unconfigured));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Long.class,
                healthy)).isZero();
        assertThat(output.getAll()).contains("ERROR", "StockNotConfiguredException", "baseProductId=" + unconfigured);
    }

    @Test
    void aComboOverAnUnconfiguredBaseReportsTheComboIdNotTheBaseId(CapturedOutput output) {
        long base = catalog.createBase(product("Base without stock", "10.00")).id();
        long combo = catalog.createCombo(product("Pack", "27.00"), base, 3).id();

        assertThatThrownBy(() -> placement.placeOrder(order(combo)))
                .isInstanceOfSatisfying(ProductNotAvailableException.class,
                        e -> assertThat(e.productIds()).isEqualTo(Set.of(combo)));

        assertThat(output.getAll()).contains("StockNotConfiguredException", "baseProductId=" + base);
    }

    @Test
    void theErrorLogCarriesNoBuyerData(CapturedOutput output) {
        long unconfigured = catalog.createBase(product("No stock row", "10.00")).id();

        assertThatThrownBy(() -> placement.placeOrder(order(unconfigured)))
                .isInstanceOf(ProductNotAvailableException.class);

        assertThat(output.getAll()).doesNotContain("Sentinel", "5559876");
    }
}
