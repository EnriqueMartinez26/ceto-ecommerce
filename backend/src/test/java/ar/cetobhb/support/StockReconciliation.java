package ar.cetobhb.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helper for CLAUDE.md rule 4 and REQ-INV-06: {@code reserved} of every base product must equal the base
 * units of its PAGO_PENDIENTE order lines. Aggregation happens in SQL (rule 8). An empty result means no drift.
 */
public final class StockReconciliation {

    private static final String STOCK_DRIFT = """
            SELECT s.product_id,
                   COALESCE(SUM(oi.base_units_total) FILTER (WHERE o.status = 'PAGO_PENDIENTE'), 0) AS expected,
                   s.reserved AS actual
              FROM inventory_stock s
              LEFT JOIN order_items oi ON oi.base_product_id = s.product_id
              LEFT JOIN orders o ON o.id = oi.order_id
             GROUP BY s.product_id, s.reserved
            HAVING s.reserved <> COALESCE(SUM(oi.base_units_total) FILTER (WHERE o.status = 'PAGO_PENDIENTE'), 0)
             ORDER BY s.product_id""";

    private StockReconciliation() {
    }

    /** Base products whose {@code reserved} differs from the pending demand, ascending by product id. */
    public static List<StockDrift> stockDrift(JdbcTemplate jdbc) {
        return jdbc.query(STOCK_DRIFT,
                (rs, row) -> new StockDrift(rs.getLong("product_id"), rs.getLong("expected"), rs.getLong("actual")));
    }

    /** One drifting base product: units expected from pending lines versus units reserved. */
    public record StockDrift(long productId, long expectedReserved, long actualReserved) {
    }
}
