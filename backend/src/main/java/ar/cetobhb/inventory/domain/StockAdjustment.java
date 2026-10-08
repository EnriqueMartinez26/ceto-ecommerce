package ar.cetobhb.inventory.domain;

public record StockAdjustment(long productId, int onHandBefore, int onHandAfter, int reserved) {
}
