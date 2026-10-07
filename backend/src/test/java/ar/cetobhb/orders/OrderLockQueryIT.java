package ar.cetobhb.orders;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.NewOrder;
import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirmation gate for verification item 8 on the REAL repository queries: native
 * {@code ... LIMIT :limit FOR UPDATE SKIP LOCKED} returning {@link Order} entities, with another real
 * transaction holding a row lock. Deliberately NOT @Transactional: every transaction here really commits.
 */
class OrderLockQueryIT extends AbstractPostgresIT {

    private static final Instant T0 = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant NOW = T0.plus(Duration.ofHours(30));

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private ExecutorService executor;
    private long productId;
    private int sequence;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        executor = Executors.newSingleThreadExecutor();
        productId = jdbc.queryForObject("INSERT INTO products (name, price) VALUES ('p', 10.00) RETURNING id",
                Long.class);
        sequence = 0;
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private long place(Instant reservedUntil) {
        sequence++;
        NewOrder data = new NewOrder("CETO-%06d".formatted(sequence), UUID.randomUUID(), "c".repeat(64), "Buyer",
                "+5493515550000", "Street 1", ShippingZone.CORDOBA_CAPITAL, reservedUntil,
                List.of(new NewOrderLine(productId, "p", false, new BigDecimal("10.00"), 1, productId, 1)));
        return tx.execute(status -> orders.saveAndFlush(Order.place(data, T0)).id());
    }

    private long placeDueIn(long hours) {
        return place(T0.plus(Duration.ofHours(hours)));
    }

    private List<Long> lockExpired(Instant now, int limit) {
        return tx.execute(status -> orders.lockExpiredBatch(now, limit).stream().map(Order::id).toList());
    }

    /** Another real transaction/connection that holds the row lock of an order until released. */
    private Future<?> holdLock(long orderId, CountDownLatch locked, CountDownLatch release) {
        return executor.submit(() -> tx.executeWithoutResult(status -> {
            orders.lockById(orderId).orElseThrow();
            locked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private void runInOtherThread(Runnable work) {
        try {
            executor.submit(work).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void expiredBatchIsOrderedByReservedUntilThenId() {
        long late = placeDueIn(3);
        long earlyFirst = placeDueIn(1);
        long earlySecond = placeDueIn(1);
        long mid = placeDueIn(2);

        assertThat(lockExpired(NOW, 10)).containsExactly(earlyFirst, earlySecond, mid, late);
    }

    @Test
    void batchLimitIsRespectedAndKeepsTheOldestDue() {
        long late = placeDueIn(3);
        long oldest = placeDueIn(1);
        long mid = placeDueIn(2);

        assertThat(lockExpired(NOW, 2)).containsExactly(oldest, mid);
        assertThat(lockExpired(NOW, 1)).containsExactly(oldest);
        assertThat(late).isNotEqualTo(oldest);
    }

    @Test
    void onlyPendingOrdersDueAtOrBeforeNowAreReturnedAndTheBoundaryIsInclusive() {
        long due = placeDueIn(1);
        long boundary = place(NOW);
        placeDueIn(100);
        long paid = placeDueIn(2);
        long cancelled = placeDueIn(3);
        tx.executeWithoutResult(status -> {
            orders.findById(paid).orElseThrow().markPaid(T0.plusSeconds(60));
            orders.findById(cancelled).orElseThrow().cancel(CancelReason.CANCELLED_BY_BUYER, T0.plusSeconds(60));
        });

        assertThat(lockExpired(NOW, 10)).containsExactly(due, boundary);
        assertThat(lockExpired(NOW.minusNanos(1_000), 10)).containsExactly(due);
    }

    @Test
    void lockedEntitiesAreManagedAndCarryEveryColumn() {
        long id = placeDueIn(1);

        tx.executeWithoutResult(status -> {
            List<Order> batch = orders.lockExpiredBatch(NOW, 10);

            assertThat(batch).hasSize(1);
            Order order = batch.get(0);
            assertThat(em.contains(order)).isTrue();
            assertThat(order.id()).isEqualTo(id);
            assertThat(order.reservedUntil()).isEqualTo(T0.plus(Duration.ofHours(1)));
            assertThat(order.total()).isEqualByComparingTo("10.00");
            assertThat(order.items()).hasSize(1);
        });
    }

    @Test
    void rowLockedByAnotherTransactionIsSkippedWithoutWaiting() throws Exception {
        long first = placeDueIn(1);
        long second = placeDueIn(2);
        long third = placeDueIn(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdLock(first, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            List<Long> ids = lockExpired(NOW, 10);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(ids).containsExactly(second, third);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(lockExpired(NOW, 10)).containsExactly(first, second, third);
    }

    @Test
    void limitCountsOnlyRowsActuallyLocked() throws Exception {
        long first = placeDueIn(1);
        long second = placeDueIn(2);
        long third = placeDueIn(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdLock(first, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(lockExpired(NOW, 2)).containsExactly(second, third);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void lockByIdWaitsForTheHolderThenSeesTheCommittedState() throws Exception {
        long id = placeDueIn(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdLock(id, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            Future<String> blocked = waiter.submit(() -> tx.execute(status ->
                    orders.lockById(id).orElseThrow().status().name()));
            Thread.sleep(500);
            assertThat(blocked.isDone()).as("lockById blocks while another transaction holds the row").isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(blocked.get(10, TimeUnit.SECONDS)).isEqualTo("PAGO_PENDIENTE");
        } finally {
            release.countDown();
            waiter.shutdownNow();
        }
    }

    @Test
    void lockByIdReturnsEmptyForAnUnknownId() {
        Optional<Order> missing = tx.execute(status -> orders.lockById(999_999L));
        assertThat(missing).isEmpty();
    }

    @Test
    void rowAlreadyInThePersistenceContextComesBackStaleAndTheDesignAvoidsIt() {
        long id = placeDueIn(1);

        tx.executeWithoutResult(status -> {
            Order early = orders.findById(id).orElseThrow();
            assertThat(early.buyerName()).isEqualTo("Buyer");

            runInOtherThread(() -> jdbc.update("UPDATE orders SET buyer_name = 'Changed' WHERE id = ?", id));

            // Both lock queries take the lock but return the already-managed, stale instance.
            Order viaBatch = orders.lockExpiredBatch(NOW, 10).get(0);
            Order viaId = orders.lockById(id).orElseThrow();
            assertThat(viaBatch).isSameAs(early);
            assertThat(viaId).isSameAs(early);
            assertThat(early.buyerName()).isEqualTo("Buyer");

            // Mitigation 1: refresh after locking reads the committed value.
            em.refresh(early);
            assertThat(early.buyerName()).isEqualTo("Changed");
        });

        // Mitigation 2 (the design): the lock query is the FIRST read of the row in a fresh transaction.
        jdbc.update("UPDATE orders SET buyer_name = 'Changed again' WHERE id = ?", id);
        String byId = tx.execute(status -> orders.lockById(id).orElseThrow().buyerName());
        String byBatch = tx.execute(status -> orders.lockExpiredBatch(NOW, 10).get(0).buyerName());
        assertThat(byId).isEqualTo("Changed again");
        assertThat(byBatch).isEqualTo("Changed again");
    }
}
