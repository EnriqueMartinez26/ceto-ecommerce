package ar.cetobhb.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helper for CLAUDE.md rule 4 and REQ-INV-06: {@code reserved} of every base product must equal the base
 * units of its PAGO_PENDIENTE order lines. Aggregation happens in SQL (rule 8). An empty result means no drift.
 */
public final class StockReconciliation {

    /**
     * FULL JOIN on purpose: a base product with pending units but no {@code inventory_stock} row has nothing
     * reserved (actual 0), so any pending demand for it is drift. A product with neither a stock row nor pending
     * lines does not appear on either side and is not drift.
     */
    private static final String STOCK_DRIFT = """
            WITH pending AS (
                SELECT oi.base_product_id AS product_id, SUM(oi.base_units_total) AS expected
                  FROM order_items oi
                  JOIN orders o ON o.id = oi.order_id
                 WHERE o.status = 'PAGO_PENDIENTE'
                 GROUP BY oi.base_product_id)
            SELECT COALESCE(s.product_id, p.product_id) AS product_id,
                   COALESCE(p.expected, 0) AS expected,
                   COALESCE(s.reserved, 0) AS actual
              FROM inventory_stock s
              FULL JOIN pending p ON p.product_id = s.product_id
             WHERE COALESCE(s.reserved, 0) <> COALESCE(p.expected, 0)
             ORDER BY 1""";

    private StockReconciliation() {
    }

    /**
     * Base products whose {@code reserved} differs from the pending demand, ascending by product id. A missing
     * stock row counts as {@code reserved = 0}.
     */
    public static List<StockDrift> stockDrift(JdbcTemplate jdbc) {
        return jdbc.query(STOCK_DRIFT,
                (rs, row) -> new StockDrift(rs.getLong("product_id"), rs.getLong("expected"), rs.getLong("actual")));
    }

    /** One drifting base product: units expected from pending lines versus units reserved. */
    public record StockDrift(long productId, long expectedReserved, long actualReserved) {
    }
}
