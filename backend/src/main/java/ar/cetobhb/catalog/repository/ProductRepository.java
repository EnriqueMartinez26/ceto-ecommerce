package ar.cetobhb.catalog.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
