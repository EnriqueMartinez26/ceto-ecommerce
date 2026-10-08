package ar.cetobhb.catalog.service;

import ar.cetobhb.catalog.domain.CatalogRules;
import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.domain.ProductView;
import ar.cetobhb.catalog.repository.Product;
import ar.cetobhb.catalog.repository.ProductRepository;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catalog use cases. Products are never hard-deleted: they are deactivated (REQ-CAT-05). */
@Service
public class CatalogService {

    private final ProductRepository products;
    private final Clock clock;

    public CatalogService(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    @Transactional
    public ProductSnapshot createBase(NewProduct data) {
        requireValid(data);
        return products.saveAndFlush(Product.createBase(data, clock.instant())).toSnapshot();
    }

    @Transactional
    public ProductSnapshot createCombo(NewProduct data, long baseProductId, int baseUnits) {
        requireValid(data);
        CatalogRules.requireComboUnits(baseUnits);
        Product base = products.findById(baseProductId)
                .filter(p -> !p.isCombo() && p.isActive())
                .orElseThrow(() -> new ValidationException(ReasonCode.INVALID_COMBO_LINK));
        return products.saveAndFlush(Product.createCombo(data, base.id(), baseUnits, clock.instant()))
                .toSnapshot();
    }

    /** A missing NewProduct is a validation error, not an NPE; the name is checked before the prices. */
    private static void requireValid(NewProduct data) {
        if (data == null) {
            throw new ValidationException(ReasonCode.INVALID_PRODUCT_NAME);
        }
        CatalogRules.requireName(data.name());
        CatalogRules.requireValidPrices(data.price(), data.compareAtPrice());
    }

    @Transactional
    public void deactivate(long productId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new ProductNotAvailableException(Set.of(productId)));
        product.deactivate(clock.instant());
    }

    /** Any product by id, active or not (admin use); a missing id is a {@link ProductNotFoundException}. */
    @Transactional(readOnly = true)
    public ProductSnapshot findAnyIncludingInactive(long id) {
        return products.findById(id).map(Product::toSnapshot).orElseThrow(() -> new ProductNotFoundException(id));
    }

    /**
     * Active products by id; any missing or inactive id fails the whole lookup (fail closed). A combo whose base
     * product was deactivated is unavailable too (REQ-CAT-02): the failure names the combo id, since ordering it
     * would otherwise fail at reservation time.
     */
    @Transactional(readOnly = true)
    public Map<Long, ProductSnapshot> findForOrder(Collection<Long> ids) {
        Map<Long, ProductSnapshot> found = new LinkedHashMap<>();
        for (Product p : products.findAllById(ids)) {
            if (p.isActive()) {
                found.put(p.id(), p.toSnapshot());
            }
        }
        Set<Long> comboBaseIds = new HashSet<>();
        for (ProductSnapshot snapshot : found.values()) {
            if (snapshot.isCombo()) {
                comboBaseIds.add(snapshot.baseProductId());
            }
        }
        Set<Long> activeBaseIds = new HashSet<>();
        for (Product base : products.findAllById(comboBaseIds)) {
            if (base.isActive()) {
                activeBaseIds.add(base.id());
            }
        }
        found.values().removeIf(s -> s.isCombo() && !activeBaseIds.contains(s.baseProductId()));
        Set<Long> unavailable = new HashSet<>(ids);
        unavailable.removeAll(found.keySet());
        if (!unavailable.isEmpty()) {
            throw new ProductNotAvailableException(unavailable);
        }
        return found;
    }

    /**
     * Storefront list (REQ-STO-02): visible products by id ascending, at most {@code limit}. Read-only, no locks.
     *
     * @throws IllegalArgumentException when {@code limit} is below 1
     */
    @Transactional(readOnly = true)
    public List<ProductView> listVisible(int limit) {
        return products.findVisible(PageRequest.of(0, limit)).stream().map(Product::toView).toList();
    }

    /** One visible product; inactive, hidden and nonexistent ids are the same {@link ProductNotFoundException}. */
    @Transactional(readOnly = true)
    public ProductView findVisible(long id) {
        return products.findVisibleById(id).map(Product::toView).orElseThrow(() -> new ProductNotFoundException(id));
    }
}
