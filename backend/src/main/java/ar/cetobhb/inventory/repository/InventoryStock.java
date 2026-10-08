package ar.cetobhb.inventory.repository;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.domain.Persistable;

/**
 * Stock of a base product. Every mutation validates in Java first; the database CHECK
 * ({@code inventory_stock_rule1_ck}) is only the backstop (CLAUDE.md section 4, rule 1).
 */
@Entity
@Table(name = "inventory_stock")
public class InventoryStock implements Persistable<Long> {

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

    /** Assigned ids make Spring Data merge (an extra SELECT); this flag makes it persist a new row directly. */
    @Transient
    private boolean fresh = true;

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

    @Override
    public Long getId() {
        return productId;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        fresh = false;
    }

    public long free() {
        return (long) onHand - reserved;
    }

    public boolean canReserve(long units) {
        return units > 0 && free() >= units;
    }

    /** True when {@code units} of this row are reserved, so they can be released or committed. */
    public boolean hasReserved(long units) {
        return units > 0 && reserved >= units;
    }

    /** True when adding {@code units} keeps on_hand within the int column. */
    public boolean canRestock(long units) {
        return units > 0 && (long) onHand + units <= Integer.MAX_VALUE;
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

    /**
     * Admin correction of the physical count; it can never drop below what is already reserved. Breaking that is a
     * business-rule rejection (VALIDATION_ERROR), not a programming error.
     */
    public void adjustOnHand(int newOnHand, Instant now) {
        if (newOnHand < reserved) {
            throw new ValidationException(ReasonCode.STOCK_BELOW_RESERVED);
        }
        onHand = newOnHand;
        touch(now);
    }

    /**
     * Relative admin correction of the physical count (INV-A1). The sum is computed in {@code long}, so no delta can
     * overflow: a result below what is reserved (or below zero) is {@link InsufficientStockException}, a result above
     * the int column is a validation error, and a zero delta is a no-op nobody should send. {@code reserved} never
     * changes here. Everything is checked before anything is touched.
     */
    public void adjustBy(int delta, Instant now) {
        if (delta == 0) {
            throw new ValidationException(ReasonCode.INVALID_STOCK_DELTA);
        }
        long next = (long) onHand + delta;
        if (next < reserved || next < 0) {
            throw new InsufficientStockException(Set.of(productId));
        }
        if (next > Integer.MAX_VALUE) {
            throw new ValidationException(ReasonCode.STOCK_OUT_OF_RANGE);
        }
        onHand = (int) next;
        touch(now);
    }

    private void requirePositive(long units) {
        if (units <= 0) {
            throw new IllegalArgumentException("units must be positive");
        }
    }

    /** Taking more than is reserved is an invariant bug in the caller, hence IllegalStateException. */
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
