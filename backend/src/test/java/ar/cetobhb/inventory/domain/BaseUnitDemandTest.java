package ar.cetobhb.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.DomainException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BaseUnitDemandTest {

    @Test
    void cartWithBaseAndItsComboAggregatesIntoOneEntry() {
        BaseUnitDemand demand = new BaseUnitDemand();
        demand.addLine(7L, 1, 2); // base B x2
        demand.addLine(7L, 3, 2); // combo C (3 x B) x2

        assertThat(demand.asMap()).containsExactly(Map.entry(7L, 8L));
    }

    @Test
    void iterationIsAscendingByBaseProductIdRegardlessOfInsertionOrder() {
        BaseUnitDemand demand = new BaseUnitDemand();
        demand.addLine(30L, 1, 1);
        demand.addLine(5L, 2, 1);
        demand.addLine(12L, 1, 4);

        assertThat(List.copyOf(demand.asMap().keySet())).containsExactly(5L, 12L, 30L);
        assertThat(demand.productIds()).containsExactly(5L, 12L, 30L);
    }

    @ParameterizedTest(name = "units per unit={0} quantity={1} is rejected")
    @CsvSource({"0, 1", "-1, 1", "1, 0", "1, -2"})
    void nonPositiveUnitsOrQuantityAreRejected(int unitsPerUnit, int quantity) {
        BaseUnitDemand demand = new BaseUnitDemand();
        assertThatThrownBy(() -> demand.addLine(1L, unitsPerUnit, quantity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(demand.isEmpty()).isTrue();
    }

    @Test
    void exposedViewIsReadOnly() {
        BaseUnitDemand demand = new BaseUnitDemand();
        demand.addLine(1L, 1, 1);
        assertThatThrownBy(() -> demand.asMap().put(2L, 1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exceptionsCarryOnlyIds() {
        InsufficientStockException insufficient = new InsufficientStockException(Set.of(9L, 3L));
        assertThat(insufficient).isInstanceOf(DomainException.class);
        assertThat(insufficient.productIds()).containsExactly(3L, 9L);
        assertThat(insufficient.getMessage()).isEqualTo("INSUFFICIENT_STOCK[3, 9]");

        StockNotConfiguredException missing = new StockNotConfiguredException(4L);
        assertThat(missing).isInstanceOf(DomainException.class);
        assertThat(missing.productId()).isEqualTo(4L);
        assertThat(missing.getMessage()).isEqualTo("STOCK_NOT_CONFIGURED(4)");
    }
}
