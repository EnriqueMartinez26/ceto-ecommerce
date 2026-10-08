package ar.cetobhb.catalog;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.domain.ProductView;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Storefront visibility (REQ-STO-02): active products, and combos only over an active base. */
class CatalogVisibilityIT extends AbstractPostgresIT {

    @Autowired
    private CatalogService catalog;

    private List<Long> listedIds(int limit) {
        return catalog.listVisible(limit).stream().map(ProductView::id).toList();
    }

    @Test
    void listsActiveProductsOrderedByIdWithTheirTexts() {
        ProductSnapshot first = catalog.createBase(new NewProduct("First", new BigDecimal("45000"),
                new BigDecimal("50000"), "100 ml", "water", "once a day"));
        ProductSnapshot second = catalog.createBase(product("Second", "10.00"));

        List<ProductView> views = catalog.listVisible(10);

        assertThat(views).extracting(ProductView::id).containsExactly(first.id(), second.id());
        ProductView view = views.get(0);
        assertThat(view.name()).isEqualTo("First");
        assertThat(view.price()).isEqualByComparingTo("45000.00");
        assertThat(view.compareAtPrice()).isEqualByComparingTo("50000.00");
        assertThat(view.presentation()).isEqualTo("100 ml");
        assertThat(view.ingredients()).isEqualTo("water");
        assertThat(view.usage()).isEqualTo("once a day");
        assertThat(view.isCombo()).isFalse();
    }

    @Test
    void hidesInactiveProducts() {
        ProductSnapshot hidden = catalog.createBase(product("Hidden", "10.00"));
        ProductSnapshot shown = catalog.createBase(product("Shown", "20.00"));
        catalog.setActive(hidden.id(), false);

        assertThat(listedIds(10)).containsExactly(shown.id());
        assertThatThrownBy(() -> catalog.findVisible(hidden.id())).isInstanceOf(ProductNotFoundException.class);
        assertThat(catalog.findVisible(shown.id()).name()).isEqualTo("Shown");
    }

    @Test
    void showsAComboOverAnActiveBaseWithItsLink() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        ProductSnapshot combo = catalog.createCombo(product("Combo", "270.00"), base.id(), 3);

        assertThat(listedIds(10)).containsExactly(base.id(), combo.id());
        ProductView view = catalog.findVisible(combo.id());
        assertThat(view.isCombo()).isTrue();
        assertThat(view.baseProductId()).isEqualTo(base.id());
        assertThat(view.baseUnitsPerUnit()).isEqualTo(3);
    }

    @Test
    void hidesAComboWhoseBaseIsInactive() {
        ProductSnapshot base = catalog.createBase(product("Base", "100.00"));
        ProductSnapshot combo = catalog.createCombo(product("Combo", "270.00"), base.id(), 3);
        catalog.setActive(base.id(), false);

        assertThat(listedIds(10)).isEmpty();
        assertThatThrownBy(() -> catalog.findVisible(combo.id())).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void capsTheListAndKeepsTheLowestIds() {
        ProductSnapshot a = catalog.createBase(product("A", "1.00"));
        ProductSnapshot b = catalog.createBase(product("B", "2.00"));
        catalog.createBase(product("C", "3.00"));

        assertThat(listedIds(2)).containsExactly(a.id(), b.id());
    }

    @Test
    void anUnknownIdIsNotFoundAndDoesNotCarryTheStateOfAHiddenOne() {
        ProductSnapshot hidden = catalog.createBase(product("Hidden", "10.00"));
        catalog.setActive(hidden.id(), false);

        assertThatThrownBy(() -> catalog.findVisible(999_999L)).isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> catalog.findVisible(hidden.id())).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void rejectsANonPositiveLimit() {
        assertThatThrownBy(() -> catalog.listVisible(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
