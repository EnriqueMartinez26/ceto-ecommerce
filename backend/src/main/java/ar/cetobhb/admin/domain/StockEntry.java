package ar.cetobhb.admin.domain;

/**
 * One line of the admin stock view. A base product carries its own counters; a combo carries only the availability
 * derived from its base product (a combo has no stock of its own). A line whose base product has no stock row is
 * {@code configured == false} and carries no figures at all, never an error (AINV-01).
 *
 * @param comboBaseProductId the base product of a combo, null for a base product
 * @param comboBaseUnits     base units one combo consumes, null for a base product
 * @param onHand             physical units of a configured base product, null otherwise
 * @param reserved           units held by pending orders of a configured base product, null otherwise
 * @param available          free sellable units (base: on_hand minus reserved; combo: floor of that over N), null when
 *                           not configured
 * @param sellable           catalog-level sellability: the product is active and, for a combo, so is its base product
 *                           (REQ-CAT-02). The figures are kept either way, so a positive {@code available} on a line
 *                           that is not sellable must not be read as "can be bought"
 */
public record StockEntry(long productId, String name, boolean active, Long comboBaseProductId,
        Integer comboBaseUnits, boolean configured, Integer onHand, Integer reserved, Long available,
        boolean sellable) {

    public boolean isCombo() {
        return comboBaseProductId != null;
    }
}
