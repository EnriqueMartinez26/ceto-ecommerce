package ar.cetobhb.admin.controller;

import ar.cetobhb.orders.service.OrderPage;
import java.util.List;

/** One page of the admin order list, newest first; {@code nextAfter} is the cursor of the next page or null. */
record AdminOrderListResponse(List<AdminOrderRow> orders, Long nextAfter) {

    static AdminOrderListResponse from(OrderPage page) {
        return new AdminOrderListResponse(page.rows().stream().map(AdminOrderRow::from).toList(), page.nextAfter());
    }
}
