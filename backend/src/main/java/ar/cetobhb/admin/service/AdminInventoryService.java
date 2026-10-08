package ar.cetobhb.admin.service;

import ar.cetobhb.admin.domain.AdjustedStock;
import ar.cetobhb.admin.domain.AuditEntry;
import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.StockAdjustment;
import ar.cetobhb.inventory.service.InventoryService;
import java.util.Objects;
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
    private final AdminAuditService audit;

    public AdminInventoryService(InventoryService inventory, AdminAuditService audit) {
        this.inventory = inventory;
        this.audit = audit;
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
    public AdjustedStock adjust(long productId, int delta, StockAdjustmentReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (delta == 0 || delta < -MAX_DELTA || delta > MAX_DELTA) {
            throw new ValidationException(ReasonCode.INVALID_STOCK_DELTA);
        }
        StockAdjustment change = inventory.adjustBy(productId, delta);
        audit.record(AuditEntry.stockAdjusted(productId, reason, change.onHandBefore(), change.onHandAfter()));
        return AdjustedStock.of(productId, change.onHandAfter(), change.reserved());
    }
}
