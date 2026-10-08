package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.DomainException;

/**
 * The buyer phone already has the maximum number of open PAGO_PENDIENTE orders (REQ-ABU-01, D-20261007-19). The HTTP
 * layer maps it to 429 TOO_MANY_PENDING_ORDERS. Carries nothing: no phone and no count, so no message or log line
 * built from it can leak buyer data.
 */
public class TooManyPendingOrdersException extends DomainException {

    private static final long serialVersionUID = 1L;

    public TooManyPendingOrdersException() {
        super("TOO_MANY_PENDING_ORDERS");
    }
}
