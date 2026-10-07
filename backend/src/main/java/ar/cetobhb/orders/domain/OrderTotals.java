package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Server-side totals (CLAUDE.md section 4, rules 7 and 8): line total = unit price x quantity,
 * order total = sum of line totals, shipping excluded (D-20261006-03). Exact {@link BigDecimal}
 * arithmetic at scale 2; every amount must fit NUMERIC(12,2).
 */
public record OrderTotals(List<BigDecimal> lineTotals, BigDecimal total) {

    private static final int SCALE = 2;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    public OrderTotals {
        lineTotals = List.copyOf(lineTotals);
    }

    public static OrderTotals of(List<PricedLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException(ReasonCode.EMPTY_CART);
        }
        List<BigDecimal> lineTotals = lines.stream()
                .map(line -> lineTotal(line.unitPrice(), line.quantity()))
                .toList();
        BigDecimal total = lineTotals.stream().reduce(BigDecimal.ZERO.setScale(SCALE), BigDecimal::add);
        return new OrderTotals(lineTotals, requireInRange(total));
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        if (unitPrice == null
                || unitPrice.signum() <= 0
                || unitPrice.stripTrailingZeros().scale() > SCALE
                || unitPrice.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("unit price must be positive, scale <= 2 and fit NUMERIC(12,2)");
        }
        if (quantity < 1) {
            throw new ValidationException(ReasonCode.QUANTITY_BELOW_MIN);
        }
        BigDecimal exact = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return requireInRange(exact.setScale(SCALE, RoundingMode.UNNECESSARY));
    }

    private static BigDecimal requireInRange(BigDecimal amount) {
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ValidationException(ReasonCode.ORDER_TOTAL_OUT_OF_RANGE);
        }
        return amount;
    }
}
