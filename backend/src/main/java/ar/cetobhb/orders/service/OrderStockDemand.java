package ar.cetobhb.orders.service;

import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderItem;

/**
 * Builds the stock demand of existing orders from their line SNAPSHOTS, never from the current catalog (rule 6).
 * Shared by every operation that moves the stock of an already placed order (pay, cancel, expiry), so they cannot
 * disagree on how a line expands into base units.
 */
final class OrderStockDemand {

    private OrderStockDemand() {
    }

    /** Base units of one order, aggregated per base product (ascending ids by construction). */
    static BaseUnitDemand of(Order order) {
        BaseUnitDemand demand = new BaseUnitDemand();
        add(demand, order);
        return demand;
    }

    /** Adds the lines of {@code order} to {@code demand}; used to merge a whole batch into ONE demand. */
    static void add(BaseUnitDemand demand, Order order) {
        for (OrderItem item : order.items()) {
            demand.addLine(item.baseProductId(), item.baseUnitsPerUnit(), item.quantity());
        }
    }
}
