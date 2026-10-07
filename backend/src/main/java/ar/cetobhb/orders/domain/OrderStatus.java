package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle. The only place where the transition graph is defined (CLAUDE.md section 4,
 * rule 5; D-20261006-07). ENTREGADO and CANCELADO are absorbing.
 */
public enum OrderStatus {
    PAGO_PENDIENTE,
    PAGADO,
    DESPACHADO,
    ENTREGADO,
    CANCELADO;

    /** Statuses reachable in one step; a fresh copy on every call. */
    public Set<OrderStatus> allowedTargets() {
        return switch (this) {
            case PAGO_PENDIENTE -> EnumSet.of(PAGADO, CANCELADO);
            case PAGADO -> EnumSet.of(DESPACHADO, CANCELADO);
            case DESPACHADO -> EnumSet.of(ENTREGADO);
            case ENTREGADO, CANCELADO -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    /** Returns {@code target} when the edge exists, otherwise throws {@link IllegalOrderTransitionException}. */
    public OrderStatus requireTransitionTo(OrderStatus target) {
        if (target == null || !allowedTargets().contains(target)) {
            throw new IllegalOrderTransitionException(this, target);
        }
        return target;
    }

    /**
     * Validates a cancellation from this status and returns CANCELADO. The edge is checked first: a status
     * without a CANCELADO edge (DESPACHADO, ENTREGADO, CANCELADO) is an {@link IllegalOrderTransitionException}
     * whatever the reason. Only then is the reason checked: null, or EXPIRED from PAGADO (the transition is
     * legal, the reason is not; D-20261006-12, D-20261007-10), is a {@code VALIDATION_ERROR(INVALID_CANCEL_REASON)}.
     */
    public OrderStatus requireCancellation(CancelReason reason) {
        OrderStatus target = requireTransitionTo(CANCELADO);
        if (reason == null || (reason == CancelReason.EXPIRED && this != PAGO_PENDIENTE)) {
            throw new ValidationException(ReasonCode.INVALID_CANCEL_REASON);
        }
        return target;
    }
}
