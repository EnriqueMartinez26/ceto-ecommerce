package ar.cetobhb.admin.controller;

import ar.cetobhb.common.controller.ApiFormats;
import ar.cetobhb.orders.service.OrderDetail;
import java.util.List;

/**
 * The admin view of one order (AOR-03). The only response that carries the buyer's name, phone and address. Money is a
 * decimal string and every instant is ISO-8601 UTC; {@code reservedUntil} and {@code secondsLeft} are null unless the
 * order is PAGO_PENDIENTE.
 */
record AdminOrderDetailResponse(long id, String orderNumber, String status, String cancelReason, String buyerName,
        String buyerPhone, String shippingAddress, String zone, String total, String createdAt, String updatedAt,
        String paidAt, String dispatchedAt, String deliveredAt, String cancelledAt, String reservedUntil,
        Long secondsLeft, boolean overdue, List<Line> lines) {

    record Line(int lineNo, long productId, String productName, boolean combo, String unitPrice, int quantity,
            int baseUnitsPerUnit, int baseUnitsTotal, String lineTotal) {

        static Line from(OrderDetail.Line line) {
            return new Line(line.lineNo(), line.productId(), line.productName(), line.combo(),
                    ApiFormats.money(line.unitPrice()), line.quantity(), line.baseUnitsPerUnit(),
                    line.baseUnitsTotal(), ApiFormats.money(line.lineTotal()));
        }
    }

    static AdminOrderDetailResponse from(OrderDetail detail) {
        return new AdminOrderDetailResponse(detail.id(), detail.orderNumber(), detail.status().name(),
                detail.cancelReason() == null ? null : detail.cancelReason().name(), detail.buyerName(),
                detail.buyerPhoneE164(), detail.shippingAddress(), detail.zone().name(),
                ApiFormats.money(detail.total()), ApiFormats.instant(detail.createdAt()),
                ApiFormats.instant(detail.updatedAt()), ApiFormats.instant(detail.paidAt()),
                ApiFormats.instant(detail.dispatchedAt()), ApiFormats.instant(detail.deliveredAt()),
                ApiFormats.instant(detail.cancelledAt()), ApiFormats.instant(detail.reservedUntil()),
                detail.secondsLeft(), detail.overdue(), detail.lines().stream().map(Line::from).toList());
    }

    @Override
    public String toString() {
        return "AdminOrderDetailResponse[id=" + id + ", status=" + status + "]";
    }
}
