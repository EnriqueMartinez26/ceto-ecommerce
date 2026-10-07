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
     * Validates a cancellation from this status and returns CANCELADO. EXPIRED is only valid from
     * PAGO_PENDIENTE (D-20261006-12); a null reason is a validation error.
     */
    public OrderStatus requireCancellation(CancelReason reason) {
        if (reason == null) {
            throw new ValidationException(ReasonCode.INVALID_CANCEL_REASON);
        }
        OrderStatus target = requireTransitionTo(CANCELADO);
        if (reason == CancelReason.EXPIRED && this != PAGO_PENDIENTE) {
            throw new IllegalOrderTransitionException(this, target);
        }
        return target;
    }
}
