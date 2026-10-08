package ar.cetobhb.admin.service;

import ar.cetobhb.admin.domain.AuditAction;
import ar.cetobhb.admin.domain.AuditEntry;
import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
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

    public AdminCatalogService(CatalogService catalog, InventoryService inventory, AdminAuditService audit) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.audit = audit;
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
}
