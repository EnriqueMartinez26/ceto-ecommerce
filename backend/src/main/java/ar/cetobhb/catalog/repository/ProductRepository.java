package ar.cetobhb.catalog.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Storefront visibility, same rule as {@code CatalogService.findForOrder}: active, and a combo only over an active
     * base. Ordered by id and bounded by the page, so the result size is never unbounded.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.active = true
              AND (p.comboBaseProductId IS NULL
                   OR EXISTS (SELECT 1 FROM Product b WHERE b.id = p.comboBaseProductId AND b.active = true))
            ORDER BY p.id""")
    List<Product> findVisible(Pageable page);

    /**
     * Reads the base product of a combo with a shared row lock ({@code FOR SHARE}) held until the end of the
     * transaction. A concurrent deactivation of the base is an UPDATE of that row, which conflicts with the share lock,
     * so it either commits first (and this read sees the inactive base) or waits until the combo write commits. Plain
     * reads do not conflict, and the foreign key from the combo only takes {@code FOR KEY SHARE}, which a non-key
     * update does not conflict with, so neither of them closes the race. Several combos over one base share the lock
     * without blocking each other. Product rows are never locked while an order or stock lock is held, so this adds no
     * edge to the order, then stock-id lock order. It must be the first read of the row in the transaction, otherwise
     * Hibernate returns the stale managed instance. Requires a surrounding transaction (MANDATORY).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT * FROM products WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<Product> findByIdForShare(@Param("id") long id);

    /** Every product, active or not, by id ascending and bounded by the page (admin list). */
    @Query("SELECT p FROM Product p ORDER BY p.id")
    List<Product> findAllOrdered(Pageable page);

    @Query("""
            SELECT p FROM Product p
            WHERE p.id = :id AND p.active = true
              AND (p.comboBaseProductId IS NULL
                   OR EXISTS (SELECT 1 FROM Product b WHERE b.id = p.comboBaseProductId AND b.active = true))""")
    Optional<Product> findVisibleById(@Param("id") long id);
}
