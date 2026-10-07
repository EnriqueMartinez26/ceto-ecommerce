package ar.cetobhb.catalog.repository;

import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Catalog row: a base product, or a combo when it links to a base product with a unit count. */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "compare_at_price", precision = 12, scale = 2)
    private BigDecimal compareAtPrice;

    @Column(name = "presentation", columnDefinition = "text")
    private String presentation;

    @Column(name = "ingredients", columnDefinition = "text")
    private String ingredients;

    @Column(name = "usage_text", columnDefinition = "text")
    private String usage;

    @Column(name = "combo_base_product_id")
    private Long comboBaseProductId;

    @Column(name = "combo_base_units")
    private Integer comboBaseUnits;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
    }

    public static Product createBase(NewProduct data, Instant now) {
        return create(data, null, null, now);
    }

    public static Product createCombo(NewProduct data, long baseProductId, int baseUnits, Instant now) {
        return create(data, baseProductId, baseUnits, now);
    }

    private static Product create(NewProduct data, Long baseId, Integer units, Instant now) {
        Product p = new Product();
        p.name = data.name();
        p.price = data.price().setScale(2);
        p.compareAtPrice = data.compareAtPrice() == null ? null : data.compareAtPrice().setScale(2);
        p.presentation = data.presentation();
        p.ingredients = data.ingredients();
        p.usage = data.usage();
        p.comboBaseProductId = baseId;
        p.comboBaseUnits = units;
        p.active = true;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    public void deactivate(Instant now) {
        if (active) {
            active = false;
            updatedAt = now;
        }
    }

    public Long id() {
        return id;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isCombo() {
        return comboBaseProductId != null;
    }

    public ProductSnapshot toSnapshot() {
        return new ProductSnapshot(id, name, price, compareAtPrice, comboBaseProductId, comboBaseUnits);
    }
}
