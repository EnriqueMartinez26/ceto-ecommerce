package ar.cetobhb.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.repository.OrderRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The per-order fallback of the expiry tick (carry-forward S5c): a lock timeout means a lock is held for longer than
 * the wait, so the orders behind the same lock would each wait the full timeout too. The fallback stops at the first
 * one and the next tick retries; any other failure still lets the following orders go.
 */
class OrderLifecycleExpiryFallbackTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private OrderLifecycleService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OrderLifecycleService(orders, inventory, Clock.fixed(NOW, ZoneOffset.UTC), txManager);
        // The batch always fails (a locked stock row), so every test goes through the fallback.
        when(orders.lockExpiredBatch(any(), anyInt())).thenThrow(new CannotAcquireLockException("batch"));
        when(orders.findDueIds(any(), anyInt())).thenReturn(List.of(1L, 2L, 3L, 4L));
    }

    static Stream<RuntimeException> lockTimeouts() {
        return Stream.of(new CannotAcquireLockException("row"), new PessimisticLockingFailureException("row"),
                new jakarta.persistence.LockTimeoutException("row"),
                new UncategorizedSQLException("task", "sql", new SQLException("lock timeout", "55P03")),
                new IllegalStateException("wrapped", new SQLException("lock timeout", "55P03")));
    }

    @ParameterizedTest
    @MethodSource("lockTimeouts")
    void theFallbackStopsAtTheFirstLockTimeoutAndLeavesTheRestForTheNextTick(RuntimeException timeout) {
        when(orders.lockById(anyLong())).thenThrow(timeout);

        ExpiryResult result = service.expireDueOrders(10);

        assertThat(result.failedOrderIds()).containsExactly(1L);
        assertThat(result.expiredOrderIds()).isEmpty();
        verify(orders).lockById(1L);
        verify(orders, never()).lockById(2L);
        verify(orders, never()).lockById(3L);
    }

    @Test
    void anyOtherFailureStillLetsTheFollowingOrdersGo() {
        when(orders.lockById(anyLong())).thenThrow(new IllegalStateException("broken order"));

        ExpiryResult result = service.expireDueOrders(10);

        assertThat(result.failedOrderIds()).containsExactly(1L, 2L, 3L, 4L);
        verify(orders).lockById(4L);
    }

    @Test
    void aSqlStateThatIsNotALockTimeoutDoesNotStopTheFallback() {
        when(orders.lockById(anyLong()))
                .thenThrow(new IllegalStateException("x", new SQLException("deadlock", "40P01")));

        ExpiryResult result = service.expireDueOrders(10);

        assertThat(result.failedOrderIds()).containsExactly(1L, 2L, 3L, 4L);
    }
}
