package ar.cetobhb.orders.domain;

/**
 * Maximum sizes of one order, injected from configuration so the domain stays free of Spring
 * (D-20261007-13, D-20261007-05).
 *
 * @param maxUnitsPerLine  highest quantity accepted on one line
 * @param maxLinesPerOrder highest number of lines accepted in one order
 */
public record OrderLimits(int maxUnitsPerLine, int maxLinesPerOrder) {

    public OrderLimits {
        if (maxUnitsPerLine < 1 || maxLinesPerOrder < 1) {
            throw new IllegalArgumentException("order limits must be positive");
        }
    }
}
