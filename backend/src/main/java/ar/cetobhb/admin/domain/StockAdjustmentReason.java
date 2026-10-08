package ar.cetobhb.admin.domain;

/** Why stock was adjusted: a closed list instead of free text, so no personal data can end up in the audit log. */
public enum StockAdjustmentReason {
    COUNT_CORRECTION,
    RESTOCK,
    DAMAGED_OR_LOST,
    RETURN,
    OTHER
}
