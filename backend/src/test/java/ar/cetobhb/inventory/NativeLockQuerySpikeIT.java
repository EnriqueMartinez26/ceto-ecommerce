package ar.cetobhb.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.inventory.repository.InventoryStock;
import ar.cetobhb.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.List;
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
 * Spike for verification item 8 (UNCONFIRMED until this passes): a native
 * {@code ... LIMIT :limit FOR UPDATE SKIP LOCKED} query returning entities under Hibernate 7 / Boot 4.1.1.
 * Production code is not involved; the same query shape is re-proved on the real repository in the orders slice.
 * Real concurrency: this test is deliberately NOT @Transactional.
 */
class NativeLockQuerySpikeIT extends AbstractPostgresIT {

    private static final String SKIP_LOCKED = "SELECT * FROM inventory_stock ORDER BY product_id "
            + "LIMIT :limit FOR UPDATE SKIP LOCKED";

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private ExecutorService executor;
    private long first;
    private long second;
    private long third;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(txManager);
        executor = Executors.newSingleThreadExecutor();
        first = seedRow(5);
        second = seedRow(5);
        third = seedRow(5);
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private long seedRow(int onHand) {
        long id = jdbc.queryForObject(
                "INSERT INTO products (name, price) VALUES ('p', 10.00) RETURNING id", Long.class);
        jdbc.update("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    @SuppressWarnings("unchecked")
    private List<InventoryStock> lockBatch(int limit) {
        return em.createNativeQuery(SKIP_LOCKED, InventoryStock.class).setParameter("limit", limit).getResultList();
    }

    /** Another real transaction/connection that holds the row lock until released. */
    private Future<?> holdLock(long productId, CountDownLatch locked, CountDownLatch release) {
        return executor.submit(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT product_id FROM inventory_stock WHERE product_id = ? FOR UPDATE", productId);
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
    void nativeQueryWithBoundLimitReturnsManagedEntitiesWithAllColumns() {
        tx.executeWithoutResult(status -> {
            List<InventoryStock> batch = lockBatch(2);

            assertThat(batch).hasSize(2);
            assertThat(batch).allSatisfy(row -> assertThat(em.contains(row)).isTrue());
            assertThat(batch).extracting(InventoryStock::productId).containsExactly(first, second);
            assertThat(batch.get(0).onHand()).isEqualTo(5);
            assertThat(batch.get(0).reserved()).isZero();
            assertThat(batch.get(0).version()).isZero();
        });
    }

    @Test
    void rowLockedByAnotherTransactionIsSkippedWithoutWaiting() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdLock(first, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            List<Long> ids = tx.execute(status -> lockBatch(10).stream().map(InventoryStock::productId).toList());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(ids).containsExactly(second, third);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void limitCountsOnlyRowsActuallyLocked() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdLock(first, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            List<Long> ids = tx.execute(status -> lockBatch(2).stream().map(InventoryStock::productId).toList());

            assertThat(ids).containsExactly(second, third);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void rowAlreadyInPersistenceContextComesBackStaleAndTheDesignAvoidsIt() throws Exception {
        tx.executeWithoutResult(status -> {
            InventoryStock early = em.find(InventoryStock.class, second);
            assertThat(early.onHand()).isEqualTo(5);

            // Another transaction commits a change to that row (it is not locked, so it does not block).
            runInOtherThread(() -> jdbc.update("UPDATE inventory_stock SET on_hand = 9 WHERE product_id = ?", second));
            assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?",
                    Integer.class, second)).isEqualTo(9);

            // The native query locks the row but Hibernate hands back the already-managed, stale instance.
            InventoryStock viaQuery = lockBatch(10).stream()
                    .filter(row -> row.productId() == second).findFirst().orElseThrow();
            assertThat(viaQuery).isSameAs(early);
            assertThat(viaQuery.onHand()).isEqualTo(5);

            // Mitigation 1: refresh after locking reads the committed value.
            em.refresh(viaQuery);
            assertThat(viaQuery.onHand()).isEqualTo(9);
        });

        // Mitigation 2 (the design): the lock query is the FIRST read of the row in a fresh transaction,
        // i.e. a fresh persistence context, so the managed instance is built from the locked row.
        int fresh = tx.execute(status -> lockBatch(10).stream()
                .filter(row -> row.productId() == second).findFirst().orElseThrow().onHand());
        assertThat(fresh).isEqualTo(9);
    }
}
