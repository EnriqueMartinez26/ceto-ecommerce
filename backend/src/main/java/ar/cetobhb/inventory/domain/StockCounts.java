package ar.cetobhb.inventory.domain;

/** Read-only physical and reserved counters of one base product; a value, never a managed entity. */
public record StockCounts(int onHand, int reserved) {
}
