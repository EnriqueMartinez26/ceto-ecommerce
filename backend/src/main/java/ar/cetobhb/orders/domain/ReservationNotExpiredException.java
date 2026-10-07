package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.DomainException;

/** RESERVATION_NOT_EXPIRED: an order cannot expire before its reserved_until instant. */
public class ReservationNotExpiredException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ReservationNotExpiredException() {
        super("RESERVATION_NOT_EXPIRED");
    }
}
