package ar.cetobhb.common.domain;

/** Reason codes carried by {@link ValidationException} (VALIDATION_ERROR family). */
public enum ReasonCode {
    INVALID_COMBO_LINK,
    INVALID_PRICE_PAIR,
    INVALID_PRODUCT_NAME,
    STOCK_BELOW_RESERVED,
    INVALID_CANCEL_REASON,
    EMPTY_CART,
    QUANTITY_BELOW_MIN,
    ORDER_TOTAL_OUT_OF_RANGE
}
