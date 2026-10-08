package ar.cetobhb.catalog;

import static ar.cetobhb.support.CatalogFixtures.product;
import static ar.cetobhb.support.CatalogFixtures.productWithCompareAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.support.AbstractPostgresIT;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CatalogServiceIT extends AbstractPostgresIT {

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    private long productCount() {
        return jdbc.queryForObject("SELECT count(*) FROM products", Long.class);
    }

    @Test
    void createsBaseProductAndComboLinkedToIt() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        ProductSnapshot combo = catalog.createCombo(product("Combo x3", "270.00"), base.id(), 3);

        assertThat(base.baseProductId()).isEqualTo(base.id());
        assertThat(base.baseUnitsPerUnit()).isEqualTo(1);
        assertThat(combo.baseProductId()).isEqualTo(base.id());
        assertThat(combo.baseUnitsPerUnit()).isEqualTo(3);
        assertThat(combo.price()).isEqualByComparingTo("270.00");
    }

    @Test
    void rejectsComboOfComboOrMissingBaseOrTooFewUnitsAndPersistsNothing() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        ProductSnapshot combo = catalog.createCombo(product("Combo", "270.00"), base.id(), 3);
        long before = productCount();

        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> catalog.createCombo(product("C2", "1.00"), combo.id(), 2));
        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> catalog.createCombo(product("C3", "1.00"), 9999L, 2));
        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> catalog.createCombo(product("C4", "1.00"), base.id(), 1));
        assertThat(productCount()).isEqualTo(before);
    }

    @Test
    void rejectsInvalidPricePairsWithoutRounding() {
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR, () -> catalog.createBase(product("p", "0")));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR, () -> catalog.createBase(product("p", "-5.00")));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR, () -> catalog.createBase(product("p", "10.005")));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> catalog.createBase(productWithCompareAt("p", "100.00", "100.00")));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> catalog.createBase(productWithCompareAt("p", "100.00", "90.00")));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> catalog.createBase(productWithCompareAt("p", "100.00", "100.005")));
        assertThat(productCount()).isZero();
    }

    @Test
    void rejectsNullAndOutOfRangePrices() {
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR,
                () -> catalog.createBase(new NewProduct("p", null, null, null, null, null)));
        assertInvalid(ReasonCode.INVALID_PRICE_PAIR, () -> catalog.createBase(product("p", "10000000000.00")));
        assertThat(productCount()).isZero();
    }

    @Test
    void rejectsMissingBlankOrTooLongNamesAndNullDataBeforeTheDatabase() {
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> catalog.createBase(null));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> catalog.createBase(product(null, "10.00")));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> catalog.createBase(product("   ", "10.00")));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> catalog.createBase(product("a".repeat(201), "10.00")));
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> catalog.createCombo(null, base.id(), 3));
        assertInvalid(ReasonCode.INVALID_PRODUCT_NAME, () -> catalog.createCombo(product(" ", "1.00"), base.id(), 3));
        assertThat(productCount()).isEqualTo(1);
    }

    @Test
    void storesTheTrimmedName() {
        ProductSnapshot trimmed = catalog.createBase(product("  Padded  ", "10.00"));

        assertThat(trimmed.name()).isEqualTo("Padded");
    }

    @Test
    void rejectsComboOverAnInactiveBase() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        catalog.setActive(base.id(), false);
        long before = productCount();

        assertInvalid(ReasonCode.INVALID_COMBO_LINK, () -> catalog.createCombo(product("C", "1.00"), base.id(), 3));
        assertThat(productCount()).isEqualTo(before);
    }

    @Test
    void acceptsAbsentOrGreaterCompareAtAndTrailingZeroScale() {
        catalog.createBase(product("no compare", "100.00"));
        ProductSnapshot greater = catalog.createBase(productWithCompareAt("greater", "100.00", "100.01"));
        ProductSnapshot trailing = catalog.createBase(product("trailing", "10.500"));

        assertThat(greater.compareAtPrice()).isEqualByComparingTo("100.01");
        assertThat(trailing.price()).isEqualByComparingTo(new BigDecimal("10.50"));
        assertThat(productCount()).isEqualTo(3);
    }

    @Test
    void findForOrderReturnsSnapshotsIncludingComboLink() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        ProductSnapshot combo = catalog.createCombo(product("Combo", "270.00"), base.id(), 3);

        Map<Long, ProductSnapshot> found = catalog.findForOrder(List.of(combo.id(), base.id()));

        assertThat(found).containsOnlyKeys(base.id(), combo.id());
        assertThat(found.get(combo.id()).baseProductId()).isEqualTo(base.id());
        assertThat(found.get(combo.id()).baseUnitsPerUnit()).isEqualTo(3);
    }

    @Test
    void findForOrderFailsForMissingOrInactiveProducts() {
        ProductSnapshot active = catalog.createBase(product("Active", "100.00"));
        ProductSnapshot inactive = catalog.createBase(product("Inactive", "100.00"));
        catalog.setActive(inactive.id(), false);

        assertThatThrownBy(() -> catalog.findForOrder(List.of(active.id(), inactive.id(), 9999L)))
                .isInstanceOfSatisfying(ProductNotAvailableException.class,
                        e -> assertThat(e.productIds()).isEqualTo(Set.of(inactive.id(), 9999L)));
    }

    @Test
    void findForOrderFailsForAnActiveComboWhoseBaseWasDeactivated() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        ProductSnapshot combo = catalog.createCombo(product("Combo", "270.00"), base.id(), 3);
        catalog.setActive(base.id(), false);

        assertThatThrownBy(() -> catalog.findForOrder(List.of(combo.id())))
                .isInstanceOfSatisfying(ProductNotAvailableException.class,
                        e -> assertThat(e.productIds()).isEqualTo(Set.of(combo.id())));
    }

    @Test
    void deactivatingAMissingProductIsNotFound() {
        assertThatThrownBy(() -> catalog.setActive(9999L, false)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void deactivateKeepsTheRowAndNoDeleteIsExposed() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));

        catalog.setActive(base.id(), false);
        catalog.setActive(base.id(), false);

        assertThat(productCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, base.id()))
                .isFalse();
        assertThat(Arrays.stream(CatalogService.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(n -> n.toLowerCase(java.util.Locale.ROOT).contains("delete")
                        || n.toLowerCase(java.util.Locale.ROOT).contains("remove"));
    }

    private static void assertInvalid(ReasonCode reason, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
