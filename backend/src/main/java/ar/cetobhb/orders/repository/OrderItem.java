package ar.cetobhb.orders.repository;

import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.OrderTotals;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

/**
 * Immutable snapshot of one purchased line (CLAUDE.md section 4, rule 6): name and price are copied at checkout
 * and never follow the catalog afterwards. No setters; Hibernate never issues an UPDATE for it.
 */
@Entity
@Immutable
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "is_combo", nullable = false)
    private boolean combo;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "base_product_id", nullable = false)
    private long baseProductId;

    @Column(name = "base_units_per_unit", nullable = false)
    private int baseUnitsPerUnit;

    @Column(name = "base_units_total", nullable = false)
    private int baseUnitsTotal;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    protected OrderItem() {
    }

    static OrderItem snapshot(Order order, short lineNo, NewOrderLine line) {
        OrderItem item = new OrderItem();
        item.order = order;
        item.lineNo = lineNo;
        item.productId = line.productId();
        item.productName = line.productName();
        item.combo = line.combo();
        item.unitPrice = line.unitPrice();
        item.quantity = line.quantity();
        item.baseProductId = line.baseProductId();
        item.baseUnitsPerUnit = line.baseUnitsPerUnit();
        item.baseUnitsTotal = Math.multiplyExact(line.quantity(), line.baseUnitsPerUnit());
        item.lineTotal = OrderTotals.lineTotal(line.unitPrice(), line.quantity());
        return item;
    }

    public Long id() {
        return id;
    }

    public short lineNo() {
        return lineNo;
    }

    public long productId() {
        return productId;
    }

    public String productName() {
        return productName;
    }

    public boolean combo() {
        return combo;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public int quantity() {
        return quantity;
    }

    public long baseProductId() {
        return baseProductId;
    }

    public int baseUnitsPerUnit() {
        return baseUnitsPerUnit;
    }

    public int baseUnitsTotal() {
        return baseUnitsTotal;
    }

    public BigDecimal lineTotal() {
        return lineTotal;
    }
}
