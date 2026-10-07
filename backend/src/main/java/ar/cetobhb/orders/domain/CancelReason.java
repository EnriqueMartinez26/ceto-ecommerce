package ar.cetobhb.orders.domain;

/** Why an order was cancelled (D-20261006-12). Stored if and only if the status is CANCELADO. */
public enum CancelReason {
    EXPIRED,
    CANCELLED_BY_ADMIN,
    CANCELLED_BY_BUYER
}
