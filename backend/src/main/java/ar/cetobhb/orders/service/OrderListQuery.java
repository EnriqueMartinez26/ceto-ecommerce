package ar.cetobhb.orders.service;

import ar.cetobhb.orders.domain.OrderSearchKey;
import ar.cetobhb.orders.domain.OrderStatus;

/**
 * Parameters of the admin order list. Every input is bounded here too, so a caller that skipped HTTP validation
 * cannot ask for an unbounded read: a violation is a programmer error ({@link IllegalArgumentException}), the
 * public 400 reason codes belong to the HTTP layer.
 *
 * @param status filter, or null for every status
 * @param q      search text (at most {@link OrderSearchKey#MAX_QUERY_LENGTH} chars), or null/blank for no search;
 *               never logged
 * @param size   page size, 1..{@link #MAX_SIZE}
 * @param after  keyset cursor: only orders with an id lower than this (1..{@link #MAX_ID}), or null for the first page
 */
public record OrderListQuery(OrderStatus status, String q, int size, Long after) {

    public static final int MAX_SIZE = 50;
    /** Largest id a JSON client can hold exactly (2^53 - 1). */
    public static final long MAX_ID = (1L << 53) - 1;

    public OrderListQuery {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        if (after != null && (after < 1 || after > MAX_ID)) {
            throw new IllegalArgumentException("after must be between 1 and " + MAX_ID);
        }
        if (q != null && q.length() > OrderSearchKey.MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("q must be at most " + OrderSearchKey.MAX_QUERY_LENGTH + " chars");
        }
    }

    @Override
    public String toString() {
        return "OrderListQuery[status=" + status + ", size=" + size + ", after=" + after + "]";
    }
}
