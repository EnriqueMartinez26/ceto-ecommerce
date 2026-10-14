package ar.cetobhb.orders.repository;

import java.math.BigDecimal;

/** One snapshot line of an order, read by a constructor projection (no managed {@code OrderItem}). */
public record OrderLineRow(short lineNo, long productId, String productName, boolean combo, BigDecimal unitPrice,
        int quantity, int baseUnitsPerUnit, int baseUnitsTotal, BigDecimal lineTotal) {

    @Override
    public String toString() {
        return "OrderLineRow[lineNo=" + lineNo + ", productId=" + productId + "]";
    }
}
