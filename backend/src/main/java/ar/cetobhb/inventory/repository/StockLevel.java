package ar.cetobhb.inventory.repository;

/** Read-only stock counters of one product; a value, never a managed entity. */
public record StockLevel(long productId, int onHand, int reserved) {
}
