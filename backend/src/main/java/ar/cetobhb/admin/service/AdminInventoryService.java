package ar.cetobhb.admin.service;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.admin.domain.AdjustedStock;
import ar.cetobhb.admin.domain.AuditEntry;
import ar.cetobhb.admin.domain.CappedList;
import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.domain.StockEntry;
import ar.cetobhb.catalog.domain.ProductAdminView;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.Availability;
import ar.cetobhb.inventory.domain.ReservationMismatch;
import ar.cetobhb.inventory.domain.StockAdjustment;
import ar.cetobhb.inventory.domain.StockCounts;
import ar.cetobhb.inventory.service.InventoryService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin facade over the stock (AINV-02, AD-A14, AD-A20). The adjustment is ONE transaction: the relative change (which
 * takes only the stock row lock, so the global lock order is untouched), then the audit row with the counts before and
 * after and the closed-set reason. If the change is rejected nothing is audited, and if the audit insert fails the
 * change rolls back with it.
 */
@Service
public class AdminInventoryService {

    /** Largest single adjustment in either direction (D-20261008-10); the HTTP layer reuses this constant. */
    public static final int MAX_DELTA = 10_000;

    private final InventoryService inventory;
    private final CatalogService catalog;
    private final AdminAuditService audit;
    private final AdminProperties properties;

    public AdminInventoryService(InventoryService inventory, CatalogService catalog, AdminAuditService audit,
            AdminProperties properties) {
        this.inventory = inventory;
        this.catalog = catalog;
        this.audit = audit;
        this.properties = properties;
    }

    /**
     * Adds {@code delta} (negative to remove) to the physical count of a base product.
     *
     * @throws ValidationException                                          INVALID_STOCK_DELTA for zero or beyond the bound
     * @throws ar.cetobhb.inventory.domain.InsufficientStockException        when the result would drop below reserved
     * @throws ar.cetobhb.inventory.domain.StockNotAdjustableException       for a combo or a product with no stock row
     * @throws ar.cetobhb.catalog.domain.ProductNotFoundException            for an unknown product
     */
    @Transactional
    public AdjustedStock adjust(long productId, int delta, int expectedOnHand, StockAdjustmentReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (delta == 0 || delta < -MAX_DELTA || delta > MAX_DELTA) {
            throw new ValidationException(ReasonCode.INVALID_STOCK_DELTA);
        }
        StockAdjustment change = inventory.adjustBy(productId, delta, expectedOnHand);
        audit.record(AuditEntry.stockAdjusted(productId, reason, change.onHandBefore(), change.onHandAfter()));
        return AdjustedStock.of(productId, change.onHandAfter(), change.reserved());
    }

    /**
     * Every product (active or not) by id ascending, at most {@code ceto.admin.max-listed-products}, with its stock
     * figures; {@code truncated} says whether the cap cut the list short (the catalog is asked for one row more than
     * the cap). Read-only: takes no row lock and leaves no managed stock entity (INV-A2).
     *
     * <p>The product list and the stock levels are two statements under READ COMMITTED, so a concurrent change between
     * them can mix snapshots (a product created after the first read has no figures, a count moved after it shows the
     * newer value). Accepted for a read-only operator screen (see CLAUDE.md); the next refresh converges.
     */
    @Transactional(readOnly = true)
    public CappedList<StockEntry> stockView() {
        int cap = properties.maxListedProducts();
        CappedList<ProductAdminView> page = CappedList.fromFetchOfCapPlusOne(catalog.listAll(cap + 1), cap);
        List<ProductAdminView> products = page.rows();
        Map<Long, ProductAdminView> byId = new HashMap<>();
        Set<Long> baseIds = new TreeSet<>();
        for (ProductAdminView product : products) {
            byId.put(product.id(), product);
            baseIds.add(product.isCombo() ? product.comboBaseProductId() : product.id());
        }
        Map<Long, StockCounts> levels = baseIds.isEmpty() ? Map.of() : inventory.levels(baseIds);
        List<StockEntry> entries = products.stream().map(product -> toEntry(product, levels, byId)).toList();
        return new CappedList<>(entries, page.truncated());
    }

    /**
     * Base products whose reserved counter disagrees with their pending order lines, at most
     * {@code ceto.admin.max-reconciliation-rows}; {@code truncated} says whether more mismatches existed. One
     * read-only statement: no lock, no write, no audit.
     */
    @Transactional(readOnly = true)
    public CappedList<ReservationMismatch> reconciliation() {
        int cap = properties.maxReconciliationRows();
        return CappedList.fromFetchOfCapPlusOne(inventory.reservationMismatches(cap + 1), cap);
    }

    /**
     * A combo's base product is created before it and ids ascend, so the base of a listed combo is always in the same
     * (id-ordered, truncated at the end) list; if it were ever missing the line fails closed as not sellable.
     */
    private static StockEntry toEntry(ProductAdminView product, Map<Long, StockCounts> levels,
            Map<Long, ProductAdminView> byId) {
        if (product.isCombo()) {
            StockCounts base = levels.get(product.comboBaseProductId());
            Long available = base == null ? null
                    : Availability.forCombo(base.onHand(), base.reserved(), product.comboBaseUnits());
            ProductAdminView baseProduct = byId.get(product.comboBaseProductId());
            boolean sellable = product.active() && baseProduct != null && baseProduct.active();
            return new StockEntry(product.id(), product.name(), product.active(), product.comboBaseProductId(),
                    product.comboBaseUnits(), base != null, null, null, available, sellable);
        }
        StockCounts own = levels.get(product.id());
        if (own == null) {
            return new StockEntry(product.id(), product.name(), product.active(), null, null, false, null, null,
                    null, product.active());
        }
        return new StockEntry(product.id(), product.name(), product.active(), null, null, true, own.onHand(),
                own.reserved(), Availability.forBase(own.onHand(), own.reserved()), product.active());
    }
}
