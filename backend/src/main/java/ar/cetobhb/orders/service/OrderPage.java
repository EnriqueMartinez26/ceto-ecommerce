package ar.cetobhb.orders.service;

import ar.cetobhb.orders.domain.OrderSummary;
import java.util.List;

/**
 * One page of the admin order list, newest first.
 *
 * @param nextAfter the id to pass as {@code after} for the next page, or null on the last page
 */
public record OrderPage(List<OrderSummary> rows, Long nextAfter) {

    public OrderPage {
        rows = List.copyOf(rows);
    }
}
