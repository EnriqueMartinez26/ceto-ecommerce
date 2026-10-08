package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import ar.cetobhb.orders.service.PlacedOrder.PlacedLine;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.StockReconciliation;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The placement result carries status, zone and the snapshot lines (REQ-ORD-01), for new orders and for replays:
 * the result always mirrors the persisted order, never the current catalog.
 */
class PlacedOrderDetailsIT extends AbstractPostgresIT {

    private static final Buyer BUYER =
            new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.RESTO_DEL_PAIS);

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private long base(String name, String price, int onHand) {
        long id = catalog.createBase(product(name, price)).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private static PlaceOrderCommand command(UUID key, PlaceOrderCommand.Line... lines) {
        return new PlaceOrderCommand(key.toString(), BUYER, List.of(lines));
    }

    private static PlaceOrderCommand.Line line(long productId, int quantity) {
        return new PlaceOrderCommand.Line(productId, quantity);
    }

    @Test
    void aNewOrderCarriesStatusZoneAndItsLinesOrderedByProductId() {
        long a = base("Alpha", "100.00", 10);
        long b = base("Beta", "45000.50", 10);

        PlacedOrder placed = placement.placeOrder(command(UUID.randomUUID(), line(b, 2), line(a, 3)));

        assertThat(placed.replayed()).isFalse();
        assertThat(placed.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(placed.zone()).isEqualTo(ShippingZone.RESTO_DEL_PAIS);
        assertThat(placed.lines()).containsExactly(
                new PlacedLine(a, "Alpha", 3, new BigDecimal("100.00"), new BigDecimal("300.00")),
                new PlacedLine(b, "Beta", 2, new BigDecimal("45000.50"), new BigDecimal("90001.00")));
        assertThat(placed.total()).isEqualByComparingTo("90301.00");
    }

    @Test
    void aComboLineIsSnapshottedAsTheComboItself() {
        long b = base("Base", "10.00", 10);
        long combo = catalog.createCombo(product("Pack", "27.00"), b, 3).id();

        PlacedOrder placed = placement.placeOrder(command(UUID.randomUUID(), line(combo, 2)));

        assertThat(placed.lines()).containsExactly(
                new PlacedLine(combo, "Pack", 2, new BigDecimal("27.00"), new BigDecimal("54.00")));
    }

    @Test
    void aReplayReturnsTheSameLinesEvenAfterThePriceAndNameChanged() {
        long a = base("Alpha", "100.00", 10);
        UUID key = UUID.randomUUID();
        PlacedOrder first = placement.placeOrder(command(key, line(a, 2)));
        jdbc.update("UPDATE products SET price = 999.00, name = 'Renamed' WHERE id = ?", a);

        PlacedOrder again = placement.placeOrder(command(key, line(a, 2)));

        assertThat(again.replayed()).isTrue();
        assertThat(again.lines()).isEqualTo(first.lines());
        assertThat(again.lines()).containsExactly(
                new PlacedLine(a, "Alpha", 2, new BigDecimal("100.00"), new BigDecimal("200.00")));
        assertThat(again.status()).isEqualTo(first.status());
        assertThat(again.zone()).isEqualTo(first.zone());
        assertThat(again.total()).isEqualByComparingTo(first.total());
    }

    @Test
    void aReplayOfACancelledOrderReportsCancelledWithTheSameLines() {
        long a = base("Alpha", "100.00", 10);
        UUID key = UUID.randomUUID();
        PlacedOrder first = placement.placeOrder(command(key, line(a, 2)));
        jdbc.update("UPDATE orders SET status = 'CANCELADO', cancel_reason = 'CANCELLED_BY_ADMIN',"
                + " cancelled_at = now() WHERE id = ?", first.orderId());
        jdbc.update("UPDATE inventory_stock SET reserved = 0 WHERE product_id = ?", a);

        PlacedOrder again = placement.placeOrder(command(key, line(a, 2)));

        assertThat(again.replayed()).isTrue();
        assertThat(again.status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(again.lines()).isEqualTo(first.lines());
    }

    @Test
    void theLinesOfAResultCannotBeModified() {
        long a = base("Alpha", "100.00", 10);

        PlacedOrder placed = placement.placeOrder(command(UUID.randomUUID(), line(a, 1)));

        assertThatThrownBy(() -> placed.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
