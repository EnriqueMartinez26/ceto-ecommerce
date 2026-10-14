package ar.cetobhb.admin.controller;

import ar.cetobhb.common.controller.ApiFormats;
import ar.cetobhb.orders.domain.OrderSummary;

/**
 * One row of the admin order list (AOR-01, AOR-08). It has the buyer's name and phone so the operator can find the
 * order, and never the address or the zone. {@code reservedUntil} is set only for a PAGO_PENDIENTE order and
 * {@code overdue} tells a deadline that passed (the expiry job has not taken the order yet) from one that did not.
 */
record AdminOrderRow(long id, String orderNumber, String status, String buyerName, String buyerPhone, String total,
        String createdAt, String reservedUntil, boolean overdue) {

    @Override
    public String toString() {
        return "AdminOrderRow[id=" + id + ", status=" + status + "]";
    }

    static AdminOrderRow from(OrderSummary summary) {
        return new AdminOrderRow(summary.id(), summary.orderNumber(), summary.status().name(), summary.buyerName(),
                summary.buyerPhoneE164(), ApiFormats.money(summary.total()), ApiFormats.instant(summary.createdAt()),
                ApiFormats.instant(summary.reservedUntil()), summary.overdue());
    }
}
