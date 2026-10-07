package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.DomainException;

/** ILLEGAL_TRANSITION: the requested edge is not in the order status graph. */
public class IllegalOrderTransitionException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("ILLEGAL_TRANSITION(" + from + "->" + to + ")");
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
