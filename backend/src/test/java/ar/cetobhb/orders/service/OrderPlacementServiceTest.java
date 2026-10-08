package ar.cetobhb.orders.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.config.OrderProperties;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.repository.OrderRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Invariant paths of {@link OrderPlacementService} that a real database cannot reach on demand. */
class OrderPlacementServiceTest {

    private static final PlaceOrderCommand COMMAND = new PlaceOrderCommand(UUID.randomUUID().toString(),
            new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL),
            List.of(new PlaceOrderCommand.Line(1L, 1)));

    @Test
    void aLostIdempotencyRaceWhoseWinnerRowIsMissingIsAnInvariantViolation() {
        CatalogService catalog = mock(CatalogService.class);
        OrderRepository orders = mock(OrderRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DataIntegrityViolationException lost = new DataIntegrityViolationException("duplicate key",
                new ConstraintViolationException("duplicate key", new SQLException("dup", "23505"),
                        "orders_idempotency_key_uk"));
        // The unique constraint fired, yet the lookup of the winner finds nothing: rows are never deleted, so this
        // cannot happen unless the invariant is broken.
        when(orders.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(catalog.findForOrder(any())).thenThrow(lost);
        OrderPlacementService service = new OrderPlacementService(
                new OrderProperties(24, 10, 10, 3, new OrderProperties.Expiry(100, 60_000, 10)), catalog,
                mock(InventoryService.class), orders, Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC),
                txManager);

        assertThatThrownBy(() -> service.placeOrder(COMMAND))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency")
                .hasCause(lost);
    }
}
