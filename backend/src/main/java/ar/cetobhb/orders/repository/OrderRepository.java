package ar.cetobhb.orders.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global lock order (CLAUDE.md section 4): the order row first, then stock rows by ascending product id.
 *
 * <p>The two native lock queries return entities. Hibernate hands back the instance already managed in the
 * persistence context when the row was read earlier in the same transaction, even if another transaction has
 * committed a change since: the lock is taken but the state is stale (the persistence context wins). So each lock
 * query must be the FIRST read of its row in the transaction (no earlier findById / find* of that order), or the
 * caller must {@code EntityManager.refresh} the entity after locking.
 *
 * <p>Both lock queries are {@code @Transactional(propagation = MANDATORY)}: called without a surrounding
 * transaction they throw {@code IllegalTransactionStateException} instead of taking a lock that is released at once.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumber();

    /** Locks one order row until the transaction ends. First read of the row in the transaction (see class doc). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT * FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Order> lockById(@Param("id") long id);

    /**
     * Locks up to {@code limit} unpaid orders whose reservation is due ({@code reserved_until <= now}, boundary
     * inclusive), oldest deadline first, ties by id; the order follows the partial index
     * {@code orders_pending_expiry_idx}. Rows locked by another transaction are skipped, never waited on, and
     * {@code LIMIT} counts only rows actually locked. First read of these rows in the transaction (see class doc).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT * FROM orders
             WHERE status = 'PAGO_PENDIENTE' AND reserved_until <= :now
             ORDER BY reserved_until, id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Order> lockExpiredBatch(@Param("now") Instant now, @Param("limit") int limit);

    Optional<Order> findByIdempotencyKey(UUID idempotencyKey);
}
