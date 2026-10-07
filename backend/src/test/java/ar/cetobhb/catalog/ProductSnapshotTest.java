package ar.cetobhb.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.domain.ProductSnapshot;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductSnapshotTest {

    @Test
    void aBaseProductResolvesToItselfWithOneUnit() {
        ProductSnapshot base = new ProductSnapshot(7L, "Base", new BigDecimal("10.00"), null, null, null);

        assertThat(base.isCombo()).isFalse();
        assertThat(base.baseProductId()).isEqualTo(7L);
        assertThat(base.baseUnitsPerUnit()).isEqualTo(1);
    }

    @Test
    void aComboResolvesToItsBaseProductAndUnits() {
        ProductSnapshot combo = new ProductSnapshot(9L, "Combo", new BigDecimal("25.00"), null, 7L, 3);

        assertThat(combo.isCombo()).isTrue();
        assertThat(combo.baseProductId()).isEqualTo(7L);
        assertThat(combo.baseUnitsPerUnit()).isEqualTo(3);
    }
}
