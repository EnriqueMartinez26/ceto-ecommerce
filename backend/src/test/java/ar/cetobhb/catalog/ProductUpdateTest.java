package ar.cetobhb.catalog;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.domain.ProductEdit;
import ar.cetobhb.catalog.repository.Product;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** CAT-A1 / ACAT-04 / ACAT-05 domain rules of an admin edit, without Spring or a database. */
class ProductUpdateTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-02T10:00:00Z");

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static Product base() {
        return Product.createBase(product("Base", "100.00"), CREATED);
    }

    @Test
    void aPriceChangeIsReportedAsPriceOnlyAndKeepsTheOtherGroupUnchanged() {
        Product p = base();

        ProductEdit edit = p.update("Base", money("150"), null, LATER);

        assertThat(edit.priceChanged()).isTrue();
        assertThat(edit.otherChanged()).isFalse();
        assertThat(edit.priceBefore()).isEqualByComparingTo("100.00");
        assertThat(edit.priceAfter()).isEqualByComparingTo("150.00");
        assertThat(p.price()).isEqualTo(money("150.00"));
        assertThat(p.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void aNameOrCompareAtChangeIsReportedAsOtherOnly() {
        Product p = base();

        ProductEdit renamed = p.update("  New name  ", money("100.00"), null, LATER);
        ProductEdit discounted = p.update("New name", money("100.00"), money("120"), LATER);

        assertThat(renamed.priceChanged()).isFalse();
        assertThat(renamed.otherChanged()).isTrue();
        assertThat(p.name()).isEqualTo("New name");
        assertThat(discounted.priceChanged()).isFalse();
        assertThat(discounted.otherChanged()).isTrue();
        assertThat(p.compareAtPrice()).isEqualTo(money("120.00"));
    }

    @Test
    void anUnchangedPriceWithADifferentScaleReportsNoPriceChange() {
        Product p = base();

        ProductEdit edit = p.update("Base", money("100"), null, LATER);

        assertThat(edit.changedAnything()).isFalse();
        assertThat(p.updatedAt()).isEqualTo(CREATED);
    }

    @Test
    void anIdenticalEditTouchesNothing() {
        Product p = Product.createBase(
                new ar.cetobhb.catalog.domain.NewProduct("Base", money("100.00"), money("130.00"), null, null, null),
                CREATED);

        ProductEdit edit = p.update("Base", money("100.00"), money("130.00"), LATER);

        assertThat(edit.changedAnything()).isFalse();
        assertThat(p.updatedAt()).isEqualTo(CREATED);
    }

    @Test
    void aCompareAtPriceAtOrBelowThePriceIsRejectedAndNothingChanges() {
        Product p = base();

        assertThatThrownBy(() -> p.update("Base", money("100.00"), money("100.00"), LATER))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_PRICE_PAIR));
        assertThatThrownBy(() -> p.update("Renamed", money("100.00"), money("90.00"), LATER))
                .isInstanceOf(ValidationException.class);

        assertThat(p.name()).isEqualTo("Base");
        assertThat(p.compareAtPrice()).isNull();
        assertThat(p.updatedAt()).isEqualTo(CREATED);
    }

    @Test
    void anInvalidNameOrPriceIsRejected() {
        Product p = base();

        assertThatThrownBy(() -> p.update("Bad\nname", money("100.00"), null, LATER))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_CHARACTERS));
        assertThatThrownBy(() -> p.update("Base", money("0"), null, LATER))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_PRICE_PAIR));
        assertThatThrownBy(() -> p.update("Base", money("10.001"), null, LATER))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void anEditNeverChangesTheComboLink() {
        Product combo = Product.createCombo(product("Combo", "270.00"), 7L, 3, CREATED);

        combo.update("Combo renamed", money("250.00"), null, LATER);

        assertThat(combo.comboBaseProductId()).isEqualTo(7L);
        assertThat(combo.comboBaseUnits()).isEqualTo(3);
        assertThat(combo.isCombo()).isTrue();
    }

    @Test
    void activateAndDeactivateReportWhetherTheStateChanged() {
        Product p = base();

        assertThat(p.activate(LATER)).isFalse();
        assertThat(p.updatedAt()).isEqualTo(CREATED);
        assertThat(p.deactivate(LATER)).isTrue();
        assertThat(p.isActive()).isFalse();
        assertThat(p.deactivate(LATER.plusSeconds(60))).isFalse();
        assertThat(p.updatedAt()).isEqualTo(LATER);
        assertThat(p.activate(LATER.plusSeconds(120))).isTrue();
        assertThat(p.isActive()).isTrue();
        assertThat(p.updatedAt()).isEqualTo(LATER.plusSeconds(120));
    }
}
