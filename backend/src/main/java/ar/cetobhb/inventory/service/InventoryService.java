package ar.cetobhb.inventory.service;

import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.Availability;
import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.ReservationMismatch;
import ar.cetobhb.inventory.domain.StockAdjustment;
import ar.cetobhb.inventory.domain.StockCounts;
import ar.cetobhb.inventory.domain.StockAlreadyConfiguredException;
import ar.cetobhb.inventory.domain.StaleStockException;
import ar.cetobhb.inventory.domain.StockNotAdjustableException;
import ar.cetobhb.inventory.domain.StockNotConfiguredException;
import ar.cetobhb.inventory.repository.InventoryStock;
import ar.cetobhb.inventory.repository.InventoryStockRepository;
import ar.cetobhb.inventory.repository.StockLevel;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock primitives (CLAUDE.md section 4, rules 1, 3, 4). The demand operations are MANDATORY: they join the
 * caller's transaction, which already holds the order row lock, and lock the stock rows one by one in ascending
 * product id (the key order of {@link BaseUnitDemand}). Every demand operation has the same all-or-nothing shape:
 * lock all rows ascending, VALIDATE all, then MUTATE; nothing is touched when any row is invalid. A shortage is a
 * domain exception raised before any mutation, so the database CHECK stays a backstop.
 *
 * <p>Failure contract: every exception here is a {@link RuntimeException}, so Spring marks the CALLER's transaction
 * rollback-only as soon as it leaves a service method. Callers (order services, the expiry job) must NOT catch one
 * and continue inside the same transaction; the commit would fail with an UnexpectedRollbackException. Handle it
 * in a new transaction or let it propagate.
 *
 * <p>Exception choice: a business-rule rejection (shortage, adjusting below reserved, duplicate or missing stock)
 * is a {@link ar.cetobhb.common.domain.DomainException}. Releasing or committing more than is reserved, or an
 * on_hand overflow, means the caller broke an invariant (a bug), so it stays an IllegalStateException.
 */
@Service
public class InventoryService {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String STOCK_PK = "inventory_stock_pk";

    /**
     * Largest reconciliation page: {@code ceto.admin.max-reconciliation-rows} is bounded to 1..1000 and the admin
     * facade asks for one row more than its cap to detect truncation.
     */
    public static final int MAX_MISMATCH_LIMIT = 1001;

    /** Largest id set for {@link #levels}: far below Postgres' 32767 bind parameters per statement. */
    public static final int MAX_LEVEL_IDS = 1000;

    private final InventoryStockRepository stock;
    private final CatalogService catalog;
    private final Clock clock;

    public InventoryService(InventoryStockRepository stock, CatalogService catalog, Clock clock) {
        this.stock = stock;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Creates the stock row of a base product. Combos have no stock of their own. */
    @Transactional
    public void createStock(long productId, int onHand) {
        ProductSnapshot product = catalog.findForOrder(Set.of(productId)).get(productId);
        if (product.isCombo()) {
            throw new ValidationException(ReasonCode.INVALID_COMBO_LINK);
        }
        try {
            // The entity is Persistable, so this is a plain INSERT (no merge SELECT); the primary key is the one
            // arbiter, which also covers two concurrent creations of the same row.
            stock.saveAndFlush(InventoryStock.create(productId, onHand, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateStock(e)) {
                throw new StockAlreadyConfiguredException(productId);
            }
            throw e;
        }
    }

    /** True only when the root cause is a unique/primary key violation (SQLState 23505 or the stock PK name). */
    static boolean isDuplicateStock(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            if (cause instanceof ConstraintViolationException violation
                    && STOCK_PK.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(BaseUnitDemand demand) {
        List<InventoryStock> rows = lockAscending(demand);
        Set<Long> shortProducts = new TreeSet<>();
        for (InventoryStock row : rows) {
            if (!row.canReserve(demand.asMap().get(row.productId()))) {
                shortProducts.add(row.productId());
            }
        }
        if (!shortProducts.isEmpty()) {
            throw new InsufficientStockException(shortProducts);
        }
        Instant now = clock.instant();
        rows.forEach(row -> row.reserve(demand.asMap().get(row.productId()), now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(BaseUnitDemand demand) {
        List<InventoryStock> rows = lockAndValidate(demand, InventoryStock::hasReserved, "release beyond reserved");
        Instant now = clock.instant();
        rows.forEach(row -> row.release(demand.asMap().get(row.productId()), now));
    }

    /** Payment: reserved units leave the shelf for good. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void commitSale(BaseUnitDemand demand) {
        List<InventoryStock> rows = lockAndValidate(demand, InventoryStock::hasReserved, "commit beyond reserved");
        Instant now = clock.instant();
        rows.forEach(row -> row.commitReserved(demand.asMap().get(row.productId()), now));
    }

    /** Cancelling a paid order returns its units to on_hand. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restock(BaseUnitDemand demand) {
        List<InventoryStock> rows = lockAndValidate(demand, InventoryStock::canRestock, "restock overflow");
        Instant now = clock.instant();
        rows.forEach(row -> row.restock(demand.asMap().get(row.productId()), now));
    }

    /** Admin correction of the physical count. */
    @Transactional
    public void adjust(long productId, int newOnHand) {
        InventoryStock row = stock.lockByProductId(productId)
                .orElseThrow(() -> new StockNotConfiguredException(productId));
        row.adjustOnHand(newOnHand, clock.instant());
    }

    /**
     * Admin correction of the physical count by a delta (INV-A1). The product must exist (any state, so a deactivated
     * one can still be corrected) and must be a base product that already has a stock row: a combo has no stock of its
     * own and a missing row is never auto-created. The row lock is the first read of the stock row, so the figures
     * returned are the committed ones. The row is then compared with {@code expectedOnHand}, the count the caller saw
     * (compare-and-set, D-20261008-48): a mismatch is a {@link StaleStockException}, so a retried or out-of-date
     * adjustment changes nothing. The Java check in {@link InventoryStock#adjustBy} runs before the CHECK
     * constraint that backs it.
     */
    @Transactional
    public StockAdjustment adjustBy(long productId, int delta, int expectedOnHand) {
        if (catalog.findAnyIncludingInactive(productId).isCombo()) {
            throw new StockNotAdjustableException(productId);
        }
        InventoryStock row = stock.lockByProductId(productId)
                .orElseThrow(() -> new StockNotAdjustableException(productId));
        if (row.onHand() != expectedOnHand) {
            throw new StaleStockException(productId);
        }
        int before = row.onHand();
        row.adjustBy(delta, clock.instant());
        return new StockAdjustment(productId, before, row.onHand(), row.reserved());
    }

    /**
     * Free base units per base product for the storefront; a product without a stock row has no key. Takes no lock
     * and leaves no managed stock row (see {@link InventoryStockRepository#findLevels}).
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> availableByBase(Collection<Long> baseIds) {
        if (baseIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> free = new LinkedHashMap<>();
        for (StockLevel level : stock.findLevels(baseIds)) {
            free.put(level.productId(), Availability.forBase(level.onHand(), level.reserved()));
        }
        return free;
    }

    /**
     * Stock counters per product, ascending by id, for the admin stock view. A product without a stock row (or an
     * unknown id) has no key. Takes no lock and leaves no managed stock row (see
     * {@link InventoryStockRepository#findLevels}).
     */
    @Transactional(readOnly = true)
    public Map<Long, StockCounts> levels(Collection<Long> ids) {
        if (ids == null || ids.size() > MAX_LEVEL_IDS) {
            throw new IllegalArgumentException("ids must be non-null and hold at most " + MAX_LEVEL_IDS + " entries");
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, StockCounts> byId = new TreeMap<>();
        for (StockLevel level : stock.findLevels(ids)) {
            byId.put(level.productId(), new StockCounts(level.onHand(), level.reserved()));
        }
        return byId;
    }

    /**
     * Base products whose reserved counter disagrees with their pending order lines (INV-A2), at most {@code limit}
     * rows ascending by id. One read-only statement: no lock, no write.
     */
    @Transactional(readOnly = true)
    public List<ReservationMismatch> reservationMismatches(int limit) {
        if (limit < 1 || limit > MAX_MISMATCH_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_MISMATCH_LIMIT);
        }
        return stock.findReservationMismatches(limit).stream()
                .map(row -> new ReservationMismatch(row.getProductId(), row.getReserved(), row.getExpected()))
                .toList();
    }

    /** Locks all rows ascending, then checks every row before the caller mutates any of them. */
    private List<InventoryStock> lockAndValidate(
            BaseUnitDemand demand, BiPredicate<InventoryStock, Long> valid, String failure) {
        List<InventoryStock> rows = lockAscending(demand);
        for (InventoryStock row : rows) {
            if (!valid.test(row, demand.asMap().get(row.productId()))) {
                throw new IllegalStateException(failure + " for product " + row.productId());
            }
        }
        return rows;
    }

    private List<InventoryStock> lockAscending(BaseUnitDemand demand) {
        List<InventoryStock> rows = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : demand.asMap().entrySet()) {
            long productId = entry.getKey();
            rows.add(stock.lockByProductId(productId).orElseThrow(() -> new StockNotConfiguredException(productId)));
        }
        return rows;
    }
}
