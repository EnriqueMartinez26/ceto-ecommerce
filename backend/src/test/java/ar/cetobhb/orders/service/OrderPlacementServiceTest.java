package ar.cetobhb.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.config.OrderProperties;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.NewOrder;
import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.NormalizedOrderRequest;
import ar.cetobhb.orders.domain.OrderLimits;
import ar.cetobhb.orders.domain.OrderRequestFingerprint;
import ar.cetobhb.orders.domain.OrderRequestNormalizer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
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

    @Test
    void aLostIdempotencyRaceWhoseWinnerHoldsTheSameRequestIsReplayed() {
        // Unreachable through the database since the per-phone lock serializes equal requests (the same fingerprint
        // implies the same phone); the defensive branch stays covered here.
        CatalogService catalog = mock(CatalogService.class);
        OrderRepository orders = mock(OrderRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DataIntegrityViolationException lost = new DataIntegrityViolationException("duplicate key",
                new ConstraintViolationException("duplicate key", new SQLException("dup", "23505"),
                        "orders_idempotency_key_uk"));
        NormalizedOrderRequest request = new OrderRequestNormalizer(new PhoneNumberNormalizer(),
                new OrderLimits(10, 10)).normalize(COMMAND);
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        Order winner = Order.place(new NewOrder("CETO-000001", UUID.fromString(COMMAND.idempotencyKey()),
                OrderRequestFingerprint.of(request), request.name(), request.phoneE164(), request.address(),
                request.zone(), now.plus(Duration.ofHours(24)),
                List.of(new NewOrderLine(1L, "Soap", false, BigDecimal.TEN, 1, 1L, 1))), now);
        ReflectionTestUtils.setField(winner, "id", 5L);
        // The lookup before the lock and the one under the lock miss, the one after the lost race finds the winner.
        when(orders.findByIdempotencyKey(any())).thenReturn(Optional.empty()).thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(catalog.findForOrder(any())).thenThrow(lost);
        OrderPlacementService service = new OrderPlacementService(
                new OrderProperties(24, 10, 10, 3, new OrderProperties.Expiry(100, 60_000, 10)), catalog,
                mock(InventoryService.class), orders, Clock.fixed(now, ZoneOffset.UTC), txManager);

        PlacedOrder replay = service.placeOrder(COMMAND);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderId()).isEqualTo(5L);
    }

    @Test
    void aLostIdempotencyRaceWhoseWinnerHoldsAnotherRequestIsAConflict() {
        // Deterministic stand-in for the thread-timing-dependent case: the winner committed the same key with a
        // different payload (hence a different fingerprint), so the loser must get a conflict, not a replay.
        CatalogService catalog = mock(CatalogService.class);
        OrderRepository orders = mock(OrderRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DataIntegrityViolationException lost = new DataIntegrityViolationException("duplicate key",
                new ConstraintViolationException("duplicate key", new SQLException("dup", "23505"),
                        "orders_idempotency_key_uk"));
        NormalizedOrderRequest request = new OrderRequestNormalizer(new PhoneNumberNormalizer(),
                new OrderLimits(10, 10)).normalize(COMMAND);
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        Order winner = Order.place(new NewOrder("CETO-000001", UUID.fromString(COMMAND.idempotencyKey()),
                "0".repeat(64), request.name(), request.phoneE164(), request.address(),
                request.zone(), now.plus(Duration.ofHours(24)),
                List.of(new NewOrderLine(1L, "Soap", false, BigDecimal.TEN, 3, 1L, 1))), now);
        ReflectionTestUtils.setField(winner, "id", 5L);
        when(orders.findByIdempotencyKey(any())).thenReturn(Optional.empty()).thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(catalog.findForOrder(any())).thenThrow(lost);
        OrderPlacementService service = new OrderPlacementService(
                new OrderProperties(24, 10, 10, 3, new OrderProperties.Expiry(100, 60_000, 10)), catalog,
                mock(InventoryService.class), orders, Clock.fixed(now, ZoneOffset.UTC), txManager);

        assertThatThrownBy(() -> service.placeOrder(COMMAND))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
