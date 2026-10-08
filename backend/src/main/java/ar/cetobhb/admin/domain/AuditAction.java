package ar.cetobhb.admin.domain;

/**
 * Everything the admin area records (D-20261008-11). The set is closed and mirrors the CHECK
 * {@code admin_audit_log_action_ck} (V4); {@code AuditActionIT} keeps both copies equal.
 */
public enum AuditAction {
    ORDER_PAID(AuditEntityType.ORDER),
    ORDER_DISPATCHED(AuditEntityType.ORDER),
    ORDER_DELIVERED(AuditEntityType.ORDER),
    ORDER_CANCELLED(AuditEntityType.ORDER),
    STOCK_ADJUSTED(AuditEntityType.PRODUCT),
    PRODUCT_CREATED(AuditEntityType.PRODUCT),
    PRODUCT_UPDATED(AuditEntityType.PRODUCT),
    PRICE_CHANGED(AuditEntityType.PRODUCT),
    PRODUCT_ACTIVATED(AuditEntityType.PRODUCT),
    PRODUCT_DEACTIVATED(AuditEntityType.PRODUCT);

    private final AuditEntityType entityType;

    AuditAction(AuditEntityType entityType) {
        this.entityType = entityType;
    }

    public AuditEntityType entityType() {
        return entityType;
    }
}
