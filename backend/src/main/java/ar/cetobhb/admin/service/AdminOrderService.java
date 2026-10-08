package ar.cetobhb.admin.service;

import ar.cetobhb.admin.domain.AuditAction;
import ar.cetobhb.admin.domain.AuditEntry;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.service.OrderDetail;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin facade over the order lifecycle (AOR-04, AOR-05, AD-A14). Each action is ONE transaction: the lifecycle call
 * (which takes the locks in the global order: order row, then stock rows ascending), then the audit row, then the
 * detail read. The audit insert takes no row lock, so the lock order is unchanged. An exception anywhere (an illegal
 * transition, an unknown id, a failed audit insert) rolls everything back: a fact is audited only if its transition
 * committed, and a transition never commits without its fact.
 *
 * <p>The state machine and the stock effects stay in {@link OrderLifecycleService}; this class never touches stock or
 * status directly. The detail is read last through projections, after the change, so it reflects the new state.
 */
@Service
public class AdminOrderService {

    private final OrderLifecycleService lifecycle;
    private final OrderQueryService queries;
    private final AdminAuditService audit;

    public AdminOrderService(OrderLifecycleService lifecycle, OrderQueryService queries, AdminAuditService audit) {
        this.lifecycle = lifecycle;
        this.queries = queries;
        this.audit = audit;
    }

    /** PAGO_PENDIENTE to PAGADO. */
    @Transactional
    public OrderDetail pay(long orderId) {
        lifecycle.markPaid(orderId);
        audit.record(AuditEntry.order(AuditAction.ORDER_PAID, orderId));
        return queries.detail(orderId);
    }

    /** PAGADO to DESPACHADO. */
    @Transactional
    public OrderDetail dispatch(long orderId) {
        lifecycle.dispatch(orderId);
        audit.record(AuditEntry.order(AuditAction.ORDER_DISPATCHED, orderId));
        return queries.detail(orderId);
    }

    /** DESPACHADO to ENTREGADO. */
    @Transactional
    public OrderDetail deliver(long orderId) {
        lifecycle.deliver(orderId);
        audit.record(AuditEntry.order(AuditAction.ORDER_DELIVERED, orderId));
        return queries.detail(orderId);
    }

    /** Cancellation by the administrator, from PAGO_PENDIENTE (releases) or PAGADO (returns the units to on_hand). */
    @Transactional
    public OrderDetail cancel(long orderId) {
        lifecycle.cancel(orderId, CancelReason.CANCELLED_BY_ADMIN);
        audit.record(AuditEntry.orderCancelled(orderId));
        return queries.detail(orderId);
    }
}
