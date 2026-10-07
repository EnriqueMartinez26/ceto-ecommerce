package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.inventory.domain.StockAlreadyConfiguredException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The ids the domain exceptions carry are what the HTTP layer will map to a response: they must be exact. */
class DomainExceptionPayloadTest {

    @Test
    void idempotencyConflictCarriesTheExistingOrderId() {
        assertThat(new IdempotencyConflictException(42L).existingOrderId()).isEqualTo(42L);
    }

    @Test
    void orderNotFoundCarriesTheOrderId() {
        assertThat(new OrderNotFoundException(17L).orderId()).isEqualTo(17L);
    }

    @Test
    void stockAlreadyConfiguredCarriesTheProductId() {
        assertThat(new StockAlreadyConfiguredException(8L).productId()).isEqualTo(8L);
    }

    @Test
    void productNotAvailableCarriesTheSortedIds() {
        assertThat(new ProductNotAvailableException(Set.of(9L, 3L)).productIds()).containsExactly(3L, 9L);
    }
}
