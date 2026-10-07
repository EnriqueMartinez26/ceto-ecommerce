package ar.cetobhb.orders.repository;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.NewOrder;
import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.OrderTotals;
import ar.cetobhb.orders.domain.PricedLine;
import ar.cetobhb.orders.domain.ReservationNotExpiredException;
import ar.cetobhb.orders.domain.ShippingZone;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A customer order. The status graph lives in {@link OrderStatus} (rule 5); this entity only applies it: there is
 * no setter and no generic mutator, every change goes through an intent method that validates the edge first and
 * stamps the matching timestamps together, so the DB CHECKs (cancel pair, paid_at) are a backstop.
 * {@code reserved_until} is fixed at creation and never changes (REQ-LIF-07). Timestamps come from the caller.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, length = 20)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 30)
    private CancelReason cancelReason;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "buyer_name", nullable = false, length = 120)
    private String buyerName;

    @Column(name = "buyer_phone_e164", nullable = false, length = 16)
    private String buyerPhoneE164;

    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_zone", nullable = false, length = 20)
    private ShippingZone shippingZone;

    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "reserved_until", nullable = false)
    private Instant reservedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    /** Builds a PAGO_PENDIENTE order with server-computed totals and snapshot lines numbered from 1. */
    public static Order place(NewOrder data, Instant now) {
        if (!data.reservedUntil().isAfter(now)) {
            throw new IllegalArgumentException("reserved_until must be after created_at");
        }
        OrderTotals totals = OrderTotals.of(data.lines().stream()
                .map(line -> new PricedLine(line.unitPrice(), line.quantity())).toList());
        Order order = new Order();
        order.orderNumber = data.orderNumber();
        order.status = OrderStatus.PAGO_PENDIENTE;
        order.idempotencyKey = data.idempotencyKey();
        order.requestFingerprint = data.requestFingerprint();
        order.buyerName = data.buyerName();
        order.buyerPhoneE164 = data.buyerPhoneE164();
        order.shippingAddress = data.shippingAddress();
        order.shippingZone = data.zone();
        order.total = totals.total();
        order.reservedUntil = data.reservedUntil();
        order.createdAt = now;
        order.updatedAt = now;
        short lineNo = 1;
        for (NewOrderLine line : data.lines()) {
            order.items.add(OrderItem.snapshot(order, lineNo++, line));
        }
        return order;
    }

    /** Payment is accepted even after reserved_until while the expiry job has not run (D-20261007-04). */
    public void markPaid(Instant now) {
        status = status.requireTransitionTo(OrderStatus.PAGADO);
        paidAt = now;
        updatedAt = now;
    }

    public void dispatch(Instant now) {
        status = status.requireTransitionTo(OrderStatus.DESPACHADO);
        dispatchedAt = now;
        updatedAt = now;
    }

    public void deliver(Instant now) {
        status = status.requireTransitionTo(OrderStatus.ENTREGADO);
        deliveredAt = now;
        updatedAt = now;
    }

    /** Manual cancellation. EXPIRED is reserved for {@link #expire(Instant)} and is rejected here. */
    public void cancel(CancelReason reason, Instant now) {
        status.requireCancellation(reason);
        if (reason == CancelReason.EXPIRED) {
            throw new ValidationException(ReasonCode.INVALID_CANCEL_REASON);
        }
        applyCancellation(reason, now);
    }

    /** Expiry of an unpaid reservation; due when {@code now >= reservedUntil} (inclusive). */
    public void expire(Instant now) {
        status.requireCancellation(CancelReason.EXPIRED);
        if (now.isBefore(reservedUntil)) {
            throw new ReservationNotExpiredException();
        }
        applyCancellation(CancelReason.EXPIRED, now);
    }

    private void applyCancellation(CancelReason reason, Instant now) {
        status = status.requireTransitionTo(OrderStatus.CANCELADO);
        cancelReason = reason;
        cancelledAt = now;
        updatedAt = now;
    }

    public Long id() {
        return id;
    }

    public String orderNumber() {
        return orderNumber;
    }

    public OrderStatus status() {
        return status;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public UUID idempotencyKey() {
        return idempotencyKey;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }

    public String buyerName() {
        return buyerName;
    }

    public String buyerPhoneE164() {
        return buyerPhoneE164;
    }

    public String shippingAddress() {
        return shippingAddress;
    }

    public ShippingZone shippingZone() {
        return shippingZone;
    }

    public BigDecimal total() {
        return total;
    }

    public Instant reservedUntil() {
        return reservedUntil;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public Instant dispatchedAt() {
        return dispatchedAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public long version() {
        return version;
    }

    public List<OrderItem> items() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public String toString() {
        return "Order[number=" + orderNumber + ", status=" + status + "]";
    }
}
