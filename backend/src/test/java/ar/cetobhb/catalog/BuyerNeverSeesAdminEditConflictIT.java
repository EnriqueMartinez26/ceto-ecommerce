package ar.cetobhb.catalog;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.domain.ProductEditData;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.BurstRunner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An admin price edit must never surface as a failure to a buyer (the optimistic lock belongs to the admin's edit, not
 * to checkout). Placement only reads the product, so it must neither dirty nor lock it: if it did, a buyer racing an
 * edit would get an optimistic-locking conflict. Each order line must also carry one of the prices the editor actually
 * committed (the snapshot is never a torn or invented value).
 */
class BuyerNeverSeesAdminEditConflictIT extends AbstractPostgresIT {

    private static final int BUYERS = 8;
    private static final int ORDERS_PER_BUYER = 12;
    private static final int MIN_EDITS = 10;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void buyersPlacingOrdersWhileTheAdminEditsThePriceNeverFailOnTheEdit() {
        long productId = catalog.createBase(product("Base", "100.00")).id();
        inventory.createStock(productId, 10_000);
        ConcurrentLinkedQueue<BigDecimal> committedPrices = new ConcurrentLinkedQueue<>();
        committedPrices.add(new BigDecimal("100.00"));
        ConcurrentLinkedQueue<Throwable> buyerFailures = new ConcurrentLinkedQueue<>();
        AtomicInteger ordersLeft = new AtomicInteger(BUYERS * ORDERS_PER_BUYER);
        AtomicInteger edits = new AtomicInteger();
        AtomicInteger placed = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int b = 0; b < BUYERS; b++) {
            tasks.add(() -> {
                for (int i = 0; i < ORDERS_PER_BUYER; i++) {
                    try {
                        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(),
                                List.of(new PlaceOrderCommand.Line(productId, 1))));
                        placed.incrementAndGet();
                    } catch (Throwable t) {
                        buyerFailures.add(t);
                    } finally {
                        ordersLeft.decrementAndGet();
                    }
                }
                return null;
            });
        }
        tasks.add(() -> {
            // The only writer of the product, so it can track the version itself; it keeps editing until every
            // buyer is done, and always for at least MIN_EDITS edits so the overlap is real.
            long version = 0;
            int n = 0;
            while (ordersLeft.get() > 0 || n < MIN_EDITS) {
                n++;
                BigDecimal price = new BigDecimal("100.00").add(new BigDecimal(n));
                try {
                    version = catalog.update(productId, version, new ProductEditData("Base", price, null))
                            .view().version();
                    committedPrices.add(price);
                    edits.incrementAndGet();
                } catch (RuntimeException e) {
                    // Only reachable if something else wrote the product row; re-read and keep going.
                    version = catalog.listAll(10).stream().filter(p -> p.id() == productId).findFirst()
                            .orElseThrow().version();
                }
            }
            return null;
        });

        BurstRunner.run(tasks).forEach(outcome -> assertThat(outcome.error()).as("task error").isNull());

        assertThat(edits.get()).as("edits committed while buyers ran").isGreaterThanOrEqualTo(MIN_EDITS);
        assertThat(buyerFailures)
                .as("only stock or validation outcomes may reach a buyer")
                .allSatisfy(t -> assertThat(t).isInstanceOfAny(InsufficientStockException.class,
                        ValidationException.class));
        assertThat(buyerFailures).noneMatch(BuyerNeverSeesAdminEditConflictIT::hasOptimisticLockInCauseChain);
        // Stock is ample, so every buyer actually succeeded: the guard is not vacuous.
        assertThat(placed.get()).isEqualTo(BUYERS * ORDERS_PER_BUYER);
        List<BigDecimal> linePrices = jdbc.queryForList(
                "SELECT unit_price FROM order_items WHERE product_id = ?", BigDecimal.class, productId);
        assertThat(linePrices).hasSize(BUYERS * ORDERS_PER_BUYER);
        assertThat(linePrices).allSatisfy(price -> assertThat(committedPrices)
                .anyMatch(committed -> committed.compareTo(price) == 0));
    }

    private static boolean hasOptimisticLockInCauseChain(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof OptimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }
}
