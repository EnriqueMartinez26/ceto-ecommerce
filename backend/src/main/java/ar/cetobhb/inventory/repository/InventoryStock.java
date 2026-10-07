package ar.cetobhb.inventory.repository;

import ar.cetobhb.inventory.domain.InsufficientStockException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;

/**
 * Stock of a base product. Every mutation validates in Java first; the database CHECK
 * ({@code inventory_stock_rule1_ck}) is only the backstop (CLAUDE.md section 4, rule 1).
 */
@Entity
@Table(name = "inventory_stock")
public class InventoryStock {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryStock() {
    }

    public static InventoryStock create(long productId, int onHand, Instant now) {
        if (onHand < 0) {
            throw new IllegalArgumentException("on_hand must not be negative");
        }
        InventoryStock stock = new InventoryStock();
        stock.productId = productId;
        stock.onHand = onHand;
        stock.updatedAt = now;
        return stock;
    }

    public long free() {
        return (long) onHand - reserved;
    }

    public boolean canReserve(long units) {
        return units > 0 && free() >= units;
    }

    public void reserve(long units, Instant now) {
        requirePositive(units);
        if (free() < units) {
            throw new InsufficientStockException(Set.of(productId));
        }
        reserved += (int) units;
        touch(now);
    }

    /** A paid order consumes reserved units for good. */
    public void commitReserved(long units, Instant now) {
        requireReservedAtLeast(units);
        reserved -= (int) units;
        onHand -= (int) units;
        touch(now);
    }

    /** A cancelled or expired unpaid order frees its reservation. */
    public void release(long units, Instant now) {
        requireReservedAtLeast(units);
        reserved -= (int) units;
        touch(now);
    }

    /** Cancelling a paid order, or a manual stock-in, adds physical units. */
    public void restock(long units, Instant now) {
        requirePositive(units);
        onHand = Math.toIntExact(onHand + units);
        touch(now);
    }

    /** Admin correction of the physical count; it can never drop below what is already reserved. */
    public void adjustOnHand(int newOnHand, Instant now) {
        if (newOnHand < reserved) {
            throw new IllegalArgumentException("on_hand cannot be lower than reserved");
        }
        onHand = newOnHand;
        touch(now);
    }

    private void requirePositive(long units) {
        if (units <= 0) {
            throw new IllegalArgumentException("units must be positive");
        }
    }

    private void requireReservedAtLeast(long units) {
        requirePositive(units);
        if (reserved < units) {
            throw new IllegalStateException("cannot take more than reserved");
        }
    }

    private void touch(Instant now) {
        updatedAt = now;
    }

    public long productId() {
        return productId;
    }

    public int onHand() {
        return onHand;
    }

    public int reserved() {
        return reserved;
    }

    public long version() {
        return version;
    }
}
