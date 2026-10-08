package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.DomainException;

/**
 * Another checkout of the same buyer phone is in progress (D-20261008-01). The per-phone lock never waits: the loser
 * is rejected at once, so no request holds a pooled connection while it waits. The HTTP layer maps it to
 * 503 SERVICE_BUSY with {@code Retry-After: 1}. Carries nothing: no phone, so no message or log line built from it can
 * leak buyer data.
 */
public class CheckoutInProgressException extends DomainException {

    private static final long serialVersionUID = 1L;

    public CheckoutInProgressException() {
        super("CHECKOUT_IN_PROGRESS");
    }
}
