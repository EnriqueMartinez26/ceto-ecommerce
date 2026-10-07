package ar.cetobhb.inventory.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryStockRepository extends JpaRepository<InventoryStock, Long> {

    /**
     * Locks one stock row. Callers lock rows ONE BY ONE in ascending product id (the key order of
     * BaseUnitDemand); never findAllById + PESSIMISTIC_WRITE and never IN (...), whose lock order is unspecified.
     * It must be the first read of the row in the transaction, otherwise Hibernate returns the stale managed instance.
     */
    @Query(value = "SELECT * FROM inventory_stock WHERE product_id = :id FOR UPDATE", nativeQuery = true)
    Optional<InventoryStock> lockByProductId(@Param("id") long productId);
}
