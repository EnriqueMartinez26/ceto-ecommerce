package ar.cetobhb.admin.domain;

import ar.cetobhb.orders.domain.CancelReason;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * One audit fact. It has room for an action, the entity it touched, a closed-set reason and two numbers, and nothing
 * else: no names, phones, addresses, IPs or free text (D-20261008-11). The factories are the only way to build one and
 * each enforces the shape the database CHECKs also demand, so a wrong combination fails here with a clear error and
 * the database stays the backstop.
 */
public record AuditEntry(AuditAction action, AuditEntityType entityType, long entityId, String reason,
        BigDecimal beforeValue, BigDecimal afterValue) {

    private static final Set<AuditAction> PLAIN_ORDER_ACTIONS = Set.of(
            AuditAction.ORDER_PAID, AuditAction.ORDER_DISPATCHED, AuditAction.ORDER_DELIVERED);
    private static final Set<AuditAction> PLAIN_PRODUCT_ACTIONS = Set.of(
            AuditAction.PRODUCT_CREATED, AuditAction.PRODUCT_UPDATED, AuditAction.PRODUCT_ACTIVATED,
            AuditAction.PRODUCT_DEACTIVATED);

    /** Pay, dispatch or deliver: the order id and nothing more. */
    public static AuditEntry order(AuditAction action, long orderId) {
        requireOneOf(action, PLAIN_ORDER_ACTIONS);
        return new AuditEntry(action, action.entityType(), requireId(orderId), null, null, null);
    }

    public static AuditEntry orderCancelled(long orderId) {
        return new AuditEntry(AuditAction.ORDER_CANCELLED, AuditEntityType.ORDER, requireId(orderId),
                CancelReason.CANCELLED_BY_ADMIN.name(), null, null);
    }

    /** The counts before and after, so the log shows the effect of a delta without storing a note. */
    public static AuditEntry stockAdjusted(long productId, StockAdjustmentReason reason, long before, long after) {
        Objects.requireNonNull(reason, "reason");
        return new AuditEntry(AuditAction.STOCK_ADJUSTED, AuditEntityType.PRODUCT, requireId(productId),
                reason.name(), BigDecimal.valueOf(before), BigDecimal.valueOf(after));
    }

    public static AuditEntry priceChanged(long productId, BigDecimal before, BigDecimal after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        return new AuditEntry(AuditAction.PRICE_CHANGED, AuditEntityType.PRODUCT, requireId(productId), null, before,
                after);
    }

    /** Create, update, activate or deactivate a product: the product id and nothing more. */
    public static AuditEntry product(AuditAction action, long productId) {
        requireOneOf(action, PLAIN_PRODUCT_ACTIONS);
        return new AuditEntry(action, action.entityType(), requireId(productId), null, null, null);
    }

    private static long requireId(long id) {
        if (id < 1) {
            throw new IllegalArgumentException("entity id must be at least 1");
        }
        return id;
    }

    private static void requireOneOf(AuditAction action, Set<AuditAction> allowed) {
        Objects.requireNonNull(action, "action");
        if (!allowed.contains(action)) {
            throw new IllegalArgumentException("action not allowed for this factory: " + action);
        }
    }
}
