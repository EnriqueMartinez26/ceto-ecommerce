package ar.cetobhb.orders.repository;

import ar.cetobhb.orders.domain.OrderStatus;
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

    /**
     * Ids of the due unpaid orders, same filter, ordering and limit as {@link #lockExpiredBatch} but WITHOUT locks:
     * the expiry fallback reads the candidates here and then locks and rechecks each order in its own transaction.
     */
    @Query(value = """
            SELECT id FROM orders
             WHERE status = 'PAGO_PENDIENTE' AND reserved_until <= :now
             ORDER BY reserved_until, id
             LIMIT :limit""", nativeQuery = true)
    List<Long> findDueIds(@Param("now") Instant now, @Param("limit") int limit);

    Optional<Order> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Serializes the pending-orders count-and-insert of ONE buyer phone (REQ-ABU-01) WITHOUT waiting (D-20261008-01):
     * tries to take the transaction-scoped Postgres advisory lock keyed by a 64-bit hash of the E.164 phone and
     * answers at once whether it got it. A held lock is released at commit or rollback. A blocking lock would park
     * one pooled connection per concurrent request of the phone, so the loser is rejected instead (the caller throws
     * {@code CheckoutInProgressException}). It is the FIRST lock of a placement, before the order row and the stock
     * rows (lock order: advisory(phone), order row, stock rows ascending); no other code path takes it, and since it
     * never waits it cannot close a cycle. Different phones that collide on the hash only reject each other
     * needlessly, never wrongly.
     *
     * @return true when this transaction now holds the lock (also when it already held it), false when another
     *         transaction does
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT pg_try_advisory_xact_lock(hashtextextended(:phone, 0))", nativeQuery = true)
    boolean tryLockBuyerPhone(@Param("phone") String phoneE164);

    /**
     * Orders of this phone in the given status (index {@code orders_buyer_phone_idx}). Mandatory transaction: the
     * count is only meaningful after {@link #tryLockBuyerPhone} succeeded in the same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    long countByBuyerPhoneE164AndStatus(String buyerPhoneE164, OrderStatus status);
}
