package ar.cetobhb.inventory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface InventoryStockRepository extends JpaRepository<InventoryStock, Long> {

    /**
     * Locks one stock row. Callers lock rows ONE BY ONE in ascending product id (the key order of
     * BaseUnitDemand); never findAllById + PESSIMISTIC_WRITE and never IN (...), whose lock order is unspecified.
     * It must be the first read of the row in the transaction, otherwise Hibernate returns the stale managed instance.
     * Requires a surrounding transaction (MANDATORY): outside one the lock is released at once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT * FROM inventory_stock WHERE product_id = :id FOR UPDATE", nativeQuery = true)
    Optional<InventoryStock> lockByProductId(@Param("id") long productId);

    /**
     * Non-locking read of the {@link StockLevel} counters for the storefront. It selects columns into a record, not
     * entities, so no stock row becomes managed: a later {@link #lockByProductId} in the same transaction still
     * reads the database instead of a stale instance.
     */
    @Query("""
            SELECT new ar.cetobhb.inventory.repository.StockLevel(s.productId, s.onHand, s.reserved)
            FROM InventoryStock s WHERE s.productId IN :ids""")
    List<StockLevel> findLevels(@Param("ids") Collection<Long> ids);

    /**
     * Base products whose {@code reserved} differs from the base units of their PAGO_PENDIENTE order lines, in one
     * read-only statement, ascending by product id. The FULL JOIN keeps both sides: pending demand without a stock
     * row comes back with a null {@code reserved}. No locks and no managed entities (an interface projection).
     * Callers pass a positive limit.
     */
    @Query(value = """
            SELECT COALESCE(s.product_id, p.product_id) AS "productId",
                   s.reserved AS "reserved",
                   COALESCE(p.expected, 0) AS "expected"
              FROM inventory_stock s
              FULL JOIN (SELECT oi.base_product_id AS product_id, SUM(oi.base_units_total) AS expected
                           FROM order_items oi
                           JOIN orders o ON o.id = oi.order_id
                          WHERE o.status = 'PAGO_PENDIENTE'
                          GROUP BY oi.base_product_id) p ON p.product_id = s.product_id
             WHERE s.reserved IS DISTINCT FROM COALESCE(p.expected, 0)
             ORDER BY 1
             LIMIT :limit""", nativeQuery = true)
    List<ReservationMismatchRow> findReservationMismatches(@Param("limit") int limit);
}
