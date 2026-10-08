package ar.cetobhb.checkout;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.checkout.domain.StockStatus;
import ar.cetobhb.checkout.domain.StorefrontProduct;
import ar.cetobhb.checkout.service.StorefrontService;
import ar.cetobhb.common.config.WebProperties;
import ar.cetobhb.inventory.domain.BaseUnitDemand;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.config.OrderProperties;
import ar.cetobhb.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Storefront read model (REQ-STO-02/03): visibility from the catalog, availability from stock, never a count. */
class StorefrontServiceIT extends AbstractPostgresIT {

    @Autowired
    private StorefrontService storefront;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderProperties orderProperties;

    @Autowired
    private PlatformTransactionManager txManager;

    private long baseWithStock(String name, int onHand) {
        long id = catalog.createBase(product(name, "100.00")).id();
        inventory.createStock(id, onHand);
        return id;
    }

    private void reserve(long baseId, int units) {
        BaseUnitDemand demand = new BaseUnitDemand();
        demand.addLine(baseId, 1, units);
        new TransactionTemplate(txManager).executeWithoutResult(status -> inventory.reserve(demand));
    }

    private StockStatus statusOf(List<StorefrontProduct> list, long id) {
        return list.stream().filter(p -> p.product().id() == id).findFirst().orElseThrow().availability();
    }

    @Test
    void derivesAvailabilityForBasesAndCombosWithoutExposingCounts() {
        long soldOut = baseWithStock("B", 5);
        reserve(soldOut, 5);
        long plenty = baseWithStock("C", 10);
        long comboOverSoldOut = catalog.createCombo(product("K", "270.00"), soldOut, 3).id();
        long comboOverPlenty = catalog.createCombo(product("K2", "270.00"), plenty, 3).id();

        List<StorefrontProduct> list = storefront.listProducts();

        assertThat(statusOf(list, soldOut)).isEqualTo(StockStatus.SOLD_OUT);
        assertThat(statusOf(list, plenty)).isEqualTo(StockStatus.AVAILABLE);
        assertThat(statusOf(list, comboOverSoldOut)).isEqualTo(StockStatus.SOLD_OUT);
        assertThat(statusOf(list, comboOverPlenty)).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void aComboNeedsAFullComboWorthOfFreeUnits() {
        long base = baseWithStock("B", 5);
        reserve(base, 3);
        long combo = catalog.createCombo(product("K", "270.00"), base, 3).id();

        assertThat(statusOf(storefront.listProducts(), combo)).isEqualTo(StockStatus.SOLD_OUT);
    }

    @Test
    void aProductWithoutAStockRowIsSoldOutNotAnError() {
        long unconfigured = catalog.createBase(product("Unconfigured", "10.00")).id();
        long combo = catalog.createCombo(product("K", "30.00"), unconfigured, 3).id();

        List<StorefrontProduct> list = storefront.listProducts();

        assertThat(statusOf(list, unconfigured)).isEqualTo(StockStatus.SOLD_OUT);
        assertThat(statusOf(list, combo)).isEqualTo(StockStatus.SOLD_OUT);
        assertThat(storefront.product(unconfigured).availability()).isEqualTo(StockStatus.SOLD_OUT);
    }

    @Test
    void reportsTheConfiguredMaxQuantityOnEveryProduct() {
        baseWithStock("B", 5);
        baseWithStock("C", 5);

        assertThat(storefront.listProducts()).hasSize(2)
                .allSatisfy(p -> assertThat(p.maxQuantity()).isEqualTo(orderProperties.maxUnitsPerLine()));
        assertThat(storefront.product(storefront.listProducts().get(0).product().id()).maxQuantity())
                .isEqualTo(orderProperties.maxUnitsPerLine());
    }

    @Test
    void detailCarriesTheClientTexts() {
        ProductSnapshot base = catalog.createBase(new NewProduct("Serum", new BigDecimal("45000"), null,
                "30 ml", "aloe", "twice a day"));
        inventory.createStock(base.id(), 2);

        StorefrontProduct detail = storefront.product(base.id());

        assertThat(detail.availability()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(detail.product().presentation()).isEqualTo("30 ml");
        assertThat(detail.product().ingredients()).isEqualTo("aloe");
        assertThat(detail.product().usage()).isEqualTo("twice a day");
    }

    @Test
    void inactiveHiddenAndUnknownProductsAreTheSameNotFound() {
        long inactive = baseWithStock("Inactive", 5);
        long base = baseWithStock("Base", 5);
        long combo = catalog.createCombo(product("Combo", "270.00"), base, 3).id();
        catalog.deactivate(inactive);
        catalog.deactivate(base);

        assertThat(storefront.listProducts()).isEmpty();
        for (long id : new long[] {inactive, combo, 999_999L}) {
            assertThatThrownBy(() -> storefront.product(id)).isExactlyInstanceOf(ProductNotFoundException.class);
        }
    }

    @Test
    void capsTheListAtTheConfiguredSize() {
        baseWithStock("A", 1);
        baseWithStock("B", 1);
        baseWithStock("C", 1);
        WebProperties twoProducts = new WebProperties(2, "127.0.0.1/32",
                new WebProperties.CheckoutRateLimit(10, 360, 10_000));
        StorefrontService capped = new StorefrontService(catalog, inventory, orderProperties, twoProducts);

        assertThat(capped.listProducts()).hasSize(2);
        assertThat(storefront.listProducts()).hasSize(3);
    }
}
