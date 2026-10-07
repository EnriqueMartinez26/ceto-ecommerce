package ar.cetobhb.inventory.service;

import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StockNotConfiguredException;
import ar.cetobhb.inventory.repository.InventoryStock;
import ar.cetobhb.inventory.repository.InventoryStockRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock primitives (CLAUDE.md section 4, rules 1, 3, 4). The demand operations are MANDATORY: they join the
 * caller's transaction, which already holds the order row lock, and lock the stock rows one by one in ascending
 * product id (the key order of {@link BaseUnitDemand}). A shortage is a domain exception raised before any
 * mutation, so the database CHECK stays a backstop.
 */
@Service
public class InventoryService {

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
        if (stock.existsById(productId)) {
            throw new IllegalStateException("stock already configured for product " + productId);
        }
        stock.saveAndFlush(InventoryStock.create(productId, onHand, clock.instant()));
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
        Instant now = clock.instant();
        lockAscending(demand).forEach(row -> row.release(demand.asMap().get(row.productId()), now));
    }

    /** Payment: reserved units leave the shelf for good. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void commitSale(BaseUnitDemand demand) {
        Instant now = clock.instant();
        lockAscending(demand).forEach(row -> row.commitReserved(demand.asMap().get(row.productId()), now));
    }

    /** Cancelling a paid order returns its units to on_hand. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restock(BaseUnitDemand demand) {
        Instant now = clock.instant();
        lockAscending(demand).forEach(row -> row.restock(demand.asMap().get(row.productId()), now));
    }

    /** Admin correction of the physical count. */
    @Transactional
    public void adjust(long productId, int newOnHand) {
        InventoryStock row = stock.lockByProductId(productId)
                .orElseThrow(() -> new StockNotConfiguredException(productId));
        row.adjustOnHand(newOnHand, clock.instant());
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
