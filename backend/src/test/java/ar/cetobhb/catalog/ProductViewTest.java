package ar.cetobhb.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.domain.ProductView;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductViewTest {

    @Test
    void aBaseProductResolvesToItselfWithOneUnit() {
        ProductView base = new ProductView(7L, "Base", new BigDecimal("10.00"), null, null, null, null, null, null);

        assertThat(base.isCombo()).isFalse();
        assertThat(base.baseProductId()).isEqualTo(7L);
        assertThat(base.baseUnitsPerUnit()).isEqualTo(1);
    }

    @Test
    void aComboResolvesToItsBaseProductAndUnits() {
        ProductView combo = new ProductView(9L, "Combo", new BigDecimal("25.00"), new BigDecimal("30.00"), 7L, 3,
                "100 ml", "water", "daily");

        assertThat(combo.isCombo()).isTrue();
        assertThat(combo.baseProductId()).isEqualTo(7L);
        assertThat(combo.baseUnitsPerUnit()).isEqualTo(3);
    }
}
