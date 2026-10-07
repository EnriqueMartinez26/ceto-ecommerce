package ar.cetobhb.orders.domain;

import java.math.BigDecimal;

/**
 * One priced line of an order about to be placed: the snapshot of the product at checkout time (rule 6)
 * plus how many base units it consumes.
 */
public record NewOrderLine(
        long productId,
        String productName,
        boolean combo,
        BigDecimal unitPrice,
        int quantity,
        long baseProductId,
        int baseUnitsPerUnit) {}
