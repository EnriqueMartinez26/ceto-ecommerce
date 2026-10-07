package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.DomainException;

/** ORDER_NOT_FOUND: no order with this internal id. */
public class OrderNotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final long orderId;

    public OrderNotFoundException(long orderId) {
        super("ORDER_NOT_FOUND(" + orderId + ")");
        this.orderId = orderId;
    }

    public long orderId() {
        return orderId;
    }
}
