package ar.cetobhb.admin.controller;

/**
 * Bounds shared by the admin request DTOs (HRD-11). The money bounds are WIDE on purpose: they only keep a hostile
 * number small; the business rule (more than 0, at most 9999999999.99, two decimals, the offer above the price) lives in
 * {@code CatalogRules} and answers with a reason a client can act on.
 */
final class AdminBounds {

    /** 2^53 - 1: the largest integer a JavaScript client reads exactly. */
    static final long MAX_ID = 9_007_199_254_740_991L;

    static final String MONEY_MIN = "-99999999999999";
    static final String MONEY_MAX = "99999999999999";
    static final int MONEY_INTEGER_DIGITS = 14;
    static final int MONEY_FRACTION_DIGITS = 6;

    /** Wider than the 200 characters the catalog accepts, so that a long name gets INVALID_PRODUCT_NAME. */
    static final int NAME_MAX = 1000;

    private AdminBounds() {
    }
}
