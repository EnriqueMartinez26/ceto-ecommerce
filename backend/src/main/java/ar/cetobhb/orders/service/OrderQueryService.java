package ar.cetobhb.orders.service;

import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.OrderSearchKey;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.OrderSummary;
import ar.cetobhb.orders.domain.ReservationCountdown;
import ar.cetobhb.orders.repository.OrderDetailRow;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.orders.repository.OrderSummaryRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model of the admin order views (AOR-01, AOR-02, AOR-03, AOR-08). Everything here is a projection read: no
 * row lock and no managed {@code Order} left in the persistence context, so a lock query later in the same
 * transaction (an admin action reads the detail AFTER it locked and changed the order) never meets a stale copy.
 * It never logs the search text or any buyer data.
 */
@Service
public class OrderQueryService {

    private final OrderRepository orders;
    private final Clock clock;
    private final PhoneNumberNormalizer phoneNormalizer = new PhoneNumberNormalizer();

    public OrderQueryService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * One page of orders, newest first. A search text that is not an order number or a valid phone gives an empty
     * page (never an error); blank or absent text means no search.
     */
    @Transactional(readOnly = true)
    public OrderPage list(OrderListQuery query) {
        boolean searching = query.q() != null && !query.q().isBlank();
        Optional<OrderSearchKey> key = searching
                ? OrderSearchKey.parse(query.q(), phoneNormalizer) : Optional.empty();
        if (searching && key.isEmpty()) {
            return new OrderPage(List.of(), null);
        }
        boolean anyStatus = query.status() == null;
        long before = query.after() == null ? Long.MAX_VALUE : query.after();
        List<OrderSummaryRow> fetched = orders.findPage(before, anyStatus,
                anyStatus ? OrderStatus.PAGO_PENDIENTE : query.status(), key.isEmpty(),
                key.map(OrderSearchKey::number).orElse(""), key.map(OrderSearchKey::phone).orElse(""),
                PageRequest.ofSize(query.size() + 1));
        boolean hasMore = fetched.size() > query.size();
        List<OrderSummaryRow> pageRows = hasMore ? fetched.subList(0, query.size()) : fetched;
        Long nextAfter = hasMore ? pageRows.get(pageRows.size() - 1).id() : null;
        Instant now = clock.instant();
        return new OrderPage(pageRows.stream().map(row -> summary(row, now)).toList(), nextAfter);
    }

    /** The full admin view of one order, with the countdown of an unpaid reservation computed from the clock. */
    @Transactional(readOnly = true)
    public OrderDetail detail(long orderId) {
        OrderDetailRow row = orders.findDetailRow(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        List<OrderDetail.Line> lines = orders.findLineRows(orderId).stream()
                .map(l -> new OrderDetail.Line(l.lineNo(), l.productId(), l.productName(), l.combo(),
                        l.unitPrice(), l.quantity(), l.baseUnitsPerUnit(), l.baseUnitsTotal(), l.lineTotal()))
                .toList();
        boolean pending = row.status() == OrderStatus.PAGO_PENDIENTE;
        Instant now = clock.instant();
        return new OrderDetail(row.id(), row.orderNumber(), row.status(), row.cancelReason(), row.buyerName(),
                row.buyerPhoneE164(), row.shippingAddress(), row.zone(), row.total(), row.createdAt(),
                row.updatedAt(), row.paidAt(), row.dispatchedAt(), row.deliveredAt(), row.cancelledAt(),
                pending ? row.reservedUntil() : null,
                ReservationCountdown.secondsLeft(row.status(), row.reservedUntil(), now),
                ReservationCountdown.overdue(row.status(), row.reservedUntil(), now), lines);
    }

    private static OrderSummary summary(OrderSummaryRow row, Instant now) {
        return new OrderSummary(row.id(), row.orderNumber(), row.status(), row.buyerName(), row.buyerPhoneE164(),
                row.total(), row.createdAt(),
                row.status() == OrderStatus.PAGO_PENDIENTE ? row.reservedUntil() : null,
                ReservationCountdown.overdue(row.status(), row.reservedUntil(), now));
    }
}
