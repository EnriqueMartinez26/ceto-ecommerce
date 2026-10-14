package ar.cetobhb.admin.service;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.admin.domain.AuditAction;
import ar.cetobhb.admin.domain.AuditEntry;
import ar.cetobhb.admin.domain.CappedList;
import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductAdminView;
import ar.cetobhb.catalog.domain.ProductChange;
import ar.cetobhb.catalog.domain.ProductEditData;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin facade over the catalog (AD-A14, AD-A15). Every change is ONE transaction: the catalog (and, for a base
 * product, the stock) change first, then the audit row, so a failure anywhere leaves neither the change nor the row,
 * and an audit failure takes the change with it. A rejected input writes nothing.
 *
 * <p>The base product and its stock row are created here, not in the catalog, because {@code inventory} already
 * depends on {@code catalog}: a catalog that called inventory would close a cycle (DEV-9).
 */
@Service
public class AdminCatalogService {

    private final CatalogService catalog;
    private final InventoryService inventory;
    private final AdminAuditService audit;
    private final AdminProperties properties;

    public AdminCatalogService(CatalogService catalog, InventoryService inventory, AdminAuditService audit,
            AdminProperties properties) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.audit = audit;
        this.properties = properties;
    }

    /**
     * Every product, active or not, by id ascending, at most {@code ceto.admin.max-listed-products}; {@code truncated}
     * says whether more existed (the catalog is asked for one row more than the cap).
     */
    @Transactional(readOnly = true)
    public CappedList<ProductAdminView> list() {
        int cap = properties.maxListedProducts();
        return CappedList.fromFetchOfCapPlusOne(catalog.listAll(cap + 1), cap);
    }

    /**
     * One product as stored, active or not, with its current version. The create responses are built from this read, not
     * from what the application assumes it just wrote.
     *
     * @throws ar.cetobhb.catalog.domain.ProductNotFoundException for an unknown id
     */
    @Transactional(readOnly = true)
    public ProductAdminView view(long id) {
        return catalog.findAdminView(id);
    }

    /**
     * Creates a base product together with its stock row (0 on hand, 0 reserved), so the storefront shows it as sold
     * out instead of treating a missing row as an error, and records PRODUCT_CREATED.
     *
     * @throws ar.cetobhb.common.domain.ValidationException for an invalid name or price pair; nothing is persisted
     */
    @Transactional
    public ProductSnapshot createBase(NewProduct data) {
        ProductSnapshot created = catalog.createBase(data);
        inventory.createStock(created.id(), 0);
        audit.record(AuditEntry.product(AuditAction.PRODUCT_CREATED, created.id()));
        return created;
    }

    /**
     * Creates a combo of {@code units} (2 to 100) base units over an existing, active base product and records
     * PRODUCT_CREATED. A combo has no stock row: its availability is derived from the base product.
     *
     * @throws ar.cetobhb.common.domain.ValidationException INVALID_COMBO_LINK for a bad unit count, an unknown,
     *         inactive or combo base; or an invalid name or price. Nothing is persisted.
     */
    @Transactional
    public ProductSnapshot createCombo(NewProduct data, long baseProductId, int units) {
        ProductSnapshot created = catalog.createCombo(data, baseProductId, units);
        audit.record(AuditEntry.product(AuditAction.PRODUCT_CREATED, created.id()));
        return created;
    }

    /**
     * Edits name, price and offer price of a product (a full replacement; the combo link cannot be passed). The audit
     * rows follow the field groups that really changed: PRICE_CHANGED (with the price before and after) and
     * PRODUCT_UPDATED (name or offer price). An edit that changes nothing writes no row and does not bump the version.
     *
     * @throws ar.cetobhb.catalog.domain.StaleProductVersionException when {@code expectedVersion} is not current
     * @throws ar.cetobhb.catalog.domain.ProductNotFoundException     for an unknown id
     */
    @Transactional
    public ProductAdminView update(long id, long expectedVersion, ProductEditData data) {
        Objects.requireNonNull(data, "data");
        ProductChange change = catalog.update(id, expectedVersion, data);
        if (change.priceChanged()) {
            audit.record(AuditEntry.priceChanged(id, change.priceBefore(), change.priceAfter()));
        }
        if (change.otherChanged()) {
            audit.record(AuditEntry.product(AuditAction.PRODUCT_UPDATED, id));
        }
        return change.view();
    }

    /** Puts a product back on sale; returns whether it changed. A repeat is not an error and writes no row. */
    @Transactional
    public boolean activate(long id) {
        return setActive(id, true, AuditAction.PRODUCT_ACTIVATED);
    }

    /**
     * Takes a product off sale; returns whether it changed. Existing orders, reservations and the stock are untouched,
     * so pending orders stay payable and cancellable.
     */
    @Transactional
    public boolean deactivate(long id) {
        return setActive(id, false, AuditAction.PRODUCT_DEACTIVATED);
    }

    private boolean setActive(long id, boolean active, AuditAction action) {
        boolean changed = catalog.setActive(id, active);
        if (changed) {
            audit.record(AuditEntry.product(action, id));
        }
        return changed;
    }
}
