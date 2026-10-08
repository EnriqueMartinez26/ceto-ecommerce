package ar.cetobhb.checkout.service;

import ar.cetobhb.catalog.domain.ProductView;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.checkout.domain.StockStatus;
import ar.cetobhb.checkout.domain.StorefrontProduct;
import ar.cetobhb.common.config.WebProperties;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.config.OrderProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storefront read model. It lives in {@code checkout} because it composes catalog and inventory, and inventory already
 * depends on catalog: composing in either would close a cycle. It goes through their services only, never their
 * repositories, and takes no lock (REQ-STO-03).
 */
@Service
public class StorefrontService {

    private final CatalogService catalog;
    private final InventoryService inventory;
    private final int maxQuantity;
    private final int maxProducts;

    /** The list cap comes from {@link WebProperties}, which rejects a value outside 1..500 at startup. */
    public StorefrontService(CatalogService catalog, InventoryService inventory, OrderProperties orders,
            WebProperties web) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.maxQuantity = orders.maxUnitsPerLine();
        this.maxProducts = web.storefrontMaxProducts();
    }

    /** Visible products by id ascending, at most the configured cap. */
    @Transactional(readOnly = true)
    public List<StorefrontProduct> listProducts() {
        List<ProductView> views = catalog.listVisible(maxProducts);
        Set<Long> baseIds = new HashSet<>();
        views.forEach(view -> baseIds.add(view.baseProductId()));
        Map<Long, Long> free = inventory.availableByBase(baseIds);
        return views.stream().map(view -> toStorefront(view, free)).toList();
    }

    /**
     * One visible product.
     *
     * @throws ar.cetobhb.catalog.domain.ProductNotFoundException for an inactive, hidden or unknown id alike
     */
    @Transactional(readOnly = true)
    public StorefrontProduct product(long id) {
        ProductView view = catalog.findVisible(id);
        return toStorefront(view, inventory.availableByBase(Set.of(view.baseProductId())));
    }

    private StorefrontProduct toStorefront(ProductView view, Map<Long, Long> freeByBase) {
        StockStatus status = StockStatus.of(freeByBase.get(view.baseProductId()), view.baseUnitsPerUnit());
        return new StorefrontProduct(view, status, maxQuantity);
    }
}
