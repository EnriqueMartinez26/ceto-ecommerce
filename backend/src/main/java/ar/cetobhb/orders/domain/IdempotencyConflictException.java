package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.DomainException;

/**
 * The idempotency key already belongs to an order created from a different request (D-20261006-11). The HTTP layer
 * maps it to 409 IDEMPOTENCY_CONFLICT. Carries the existing order id only, never request data.
 */
public class IdempotencyConflictException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final long existingOrderId;

    public IdempotencyConflictException(long existingOrderId) {
        super("IDEMPOTENCY_CONFLICT[order=" + existingOrderId + "]");
        this.existingOrderId = existingOrderId;
    }

    public long existingOrderId() {
        return existingOrderId;
    }
}
