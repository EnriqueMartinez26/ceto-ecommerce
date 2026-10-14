package ar.cetobhb.admin.controller;

import ar.cetobhb.admin.domain.CappedList;
import ar.cetobhb.admin.domain.StockEntry;
import java.util.List;

/**
 * The admin stock view (AINV-01): one entry per product by id, active or not. A base product carries its own counters; a
 * combo only the availability derived from its base; a base product with no stock row is {@code configured: false} and has
 * no figures. {@code truncated} is true when the cap cut the list short.
 */
record AdminStockResponse(List<Entry> products, boolean truncated) {

    /** One product. {@code sellable} is the catalog-level answer: active and, for a combo, so is its base. */
    record Entry(long productId, String name, boolean active, boolean combo, Long comboBaseProductId,
            Integer comboBaseUnits, boolean configured, Integer onHand, Integer reserved, Long available,
            boolean sellable) {

        static Entry from(StockEntry entry) {
            return new Entry(entry.productId(), entry.name(), entry.active(), entry.isCombo(),
                    entry.comboBaseProductId(), entry.comboBaseUnits(), entry.configured(), entry.onHand(),
                    entry.reserved(), entry.available(), entry.sellable());
        }

        /** The product name is not printed (CLAUDE.md section 5: no product or buyer data in logs). */
        @Override
        public String toString() {
            return "Entry[productId=" + productId + ", configured=" + configured + "]";
        }
    }

    static AdminStockResponse from(CappedList<StockEntry> view) {
        return new AdminStockResponse(view.rows().stream().map(Entry::from).toList(), view.truncated());
    }
}
