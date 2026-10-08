package ar.cetobhb.admin.domain;

import ar.cetobhb.orders.domain.CancelReason;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One audit fact. It has room for an action, the entity it touched, a closed-set reason and two numbers, and nothing
 * else: no names, phones, addresses, IPs or free text (D-20261008-11). The canonical constructor re-validates the shape
 * the database CHECKs also demand (action/entity pairing, a reason from the closed set for that action, and the
 * numeric shape), so no way of building an entry, the factories or the constructor itself, can carry free text or a
 * wrong combination. A violation fails here with a clear error and the database stays the backstop.
 */
public record AuditEntry(AuditAction action, AuditEntityType entityType, long entityId, String reason,
        BigDecimal beforeValue, BigDecimal afterValue) {

    private static final Set<String> STOCK_REASONS = Arrays.stream(StockAdjustmentReason.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private static final String CANCEL_REASON = CancelReason.CANCELLED_BY_ADMIN.name();

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entityType, "entityType");
        requireId(entityId);
        if (action.entityType() != entityType) {
            throw new IllegalArgumentException("action " + action + " does not apply to " + entityType);
        }
        boolean reasonOk = switch (action) {
            case STOCK_ADJUSTED -> reason != null && STOCK_REASONS.contains(reason);
            case ORDER_CANCELLED -> CANCEL_REASON.equals(reason);
            default -> reason == null;
        };
        if (!reasonOk) {
            throw new IllegalArgumentException("reason is not in the closed set for " + action);
        }
        boolean carriesValues = action == AuditAction.STOCK_ADJUSTED || action == AuditAction.PRICE_CHANGED;
        if (carriesValues != (beforeValue != null && afterValue != null)
                || (beforeValue == null) != (afterValue == null)) {
            throw new IllegalArgumentException("before/after values do not match " + action);
        }
    }

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
