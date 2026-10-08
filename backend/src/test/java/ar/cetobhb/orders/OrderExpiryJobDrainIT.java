package ar.cetobhb.orders;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.config.OrderProperties;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderExpiryJob;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.MutableClock;
import ar.cetobhb.support.StockReconciliation;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One job tick drains a backlog larger than a batch, within its per-tick cap (REQ-LIF-06, AD-12). The tick is run
 * by hand on a real Postgres: the job is never scheduled in tests.
 */
class OrderExpiryJobDrainIT extends AbstractPostgresIT {

    @Autowired
    private OrderLifecycleService lifecycle;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @AfterEach
    void reconciliationIsClean() {
        assertThat(StockReconciliation.stockDrift(jdbc)).isEmpty();
    }

    private OrderExpiryJob job(int batchSize, int maxBatchesPerTick) {
        return new OrderExpiryJob(lifecycle, new OrderProperties(48, 10, 20, 3,
                new OrderProperties.Expiry(batchSize, 60_000, maxBatchesPerTick)));
    }

    private long placeDueOrders(int count) {
        long product = catalog.createBase(product("B", "100.00")).id();
        inventory.createStock(product, 100);
        for (int i = 0; i < count; i++) {
            placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(),
                    List.of(new PlaceOrderCommand.Line(product, 1))));
        }
        clock.set(MutableClock.START.plus(Duration.ofHours(49)));
        return product;
    }

    private int pending() {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'PAGO_PENDIENTE'", Integer.class);
    }

    @Test
    void oneTickDrainsABacklogOfThreeBatchesWithinTheCap() {
        placeDueOrders(8);

        job(3, 10).run();

        assertThat(pending()).isZero();
    }

    @Test
    void aCapOfOneBatchLeavesTheRestForTheNextTick() {
        placeDueOrders(8);
        OrderExpiryJob job = job(3, 1);

        job.run();
        assertThat(pending()).isEqualTo(5);

        job.run();
        assertThat(pending()).isEqualTo(2);
    }
}
