package ar.cetobhb.catalog.repository;

import ar.cetobhb.catalog.domain.CatalogRules;
import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductEdit;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.domain.ProductView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    /** Optimistic lock: bumped by Hibernate on every update, so a stale edit fails instead of overwriting. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
        p.name = data.name().strip();
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

    /**
     * Admin edit of the three editable fields. The combo link (base product and units) is not a parameter, so an edit
     * cannot change it. Validates first; when nothing differs the entity is left untouched (no write, no new
     * {@code updatedAt}). The returned {@link ProductEdit} says which group of fields changed, for the audit.
     */
    public ProductEdit update(String newName, BigDecimal newPrice, BigDecimal newCompareAtPrice, Instant now) {
        CatalogRules.requireName(newName);
        CatalogRules.requireValidPrices(newPrice, newCompareAtPrice);
        String strippedName = newName.strip();
        BigDecimal scaledPrice = newPrice.setScale(2);
        BigDecimal scaledCompareAt = newCompareAtPrice == null ? null : newCompareAtPrice.setScale(2);
        BigDecimal priceBefore = price;
        boolean priceChanged = price.compareTo(scaledPrice) != 0;
        boolean otherChanged = !name.equals(strippedName) || !sameMoney(compareAtPrice, scaledCompareAt);
        if (priceChanged || otherChanged) {
            name = strippedName;
            price = scaledPrice;
            compareAtPrice = scaledCompareAt;
            updatedAt = now;
        }
        return new ProductEdit(priceBefore, price, otherChanged);
    }

    private static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** Returns whether the state changed; activating an active product is a no-op. */
    public boolean activate(Instant now) {
        if (active) {
            return false;
        }
        active = true;
        updatedAt = now;
        return true;
    }

    /** Returns whether the state changed; deactivating an inactive product is a no-op. */
    public boolean deactivate(Instant now) {
        if (active) {
            active = false;
            updatedAt = now;
            return true;
        }
        return false;
    }

    public String name() {
        return name;
    }

    public BigDecimal price() {
        return price;
    }

    public BigDecimal compareAtPrice() {
        return compareAtPrice;
    }

    public Long comboBaseProductId() {
        return comboBaseProductId;
    }

    public Integer comboBaseUnits() {
        return comboBaseUnits;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long id() {
        return id;
    }

    public long version() {
        return version;
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

    public ProductView toView() {
        return new ProductView(id, name, price, compareAtPrice, comboBaseProductId, comboBaseUnits, presentation,
                ingredients, usage);
    }
}
