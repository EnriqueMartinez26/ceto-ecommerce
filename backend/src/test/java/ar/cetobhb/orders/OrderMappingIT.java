package ar.cetobhb.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.NewOrder;
import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderItem;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order / OrderItem mapping on a real Postgres. The whole context boots under {@code ddl-auto=validate}, so a
 * mapping that drifts from V3 fails every IT; these tests add round trips, every legal lifecycle path against
 * the DB CHECKs, the immutable snapshot and the idempotency constraint name (verification item 7).
 */
class OrderMappingIT extends AbstractPostgresIT {

    private static final Instant CREATED = Instant.parse("2026-10-07T12:00:00.123456Z");
    private static final Instant RESERVED_UNTIL = CREATED.plus(Duration.ofHours(24));
    private static final Instant LATER = CREATED.plus(Duration.ofHours(2));

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private long baseId;
    private long comboId;
    private int sequence;

    @BeforeEach
    void seedProducts() {
        tx = new TransactionTemplate(txManager);
        baseId = jdbc.queryForObject("INSERT INTO products (name, price) VALUES ('base', 100.00) RETURNING id",
                Long.class);
        comboId = jdbc.queryForObject("INSERT INTO products (name, price) VALUES ('combo', 250.50) RETURNING id",
                Long.class);
        sequence = 0;
    }

    private NewOrder newOrder(UUID key, Instant reservedUntil) {
        sequence++;
        return new NewOrder("CETO-%06d".formatted(sequence), key, "b".repeat(64), "Buyer Name", "+5493515550000",
                "Street 1", ShippingZone.RESTO_DEL_PAIS, reservedUntil, List.of(
                        new NewOrderLine(baseId, "Base", false, new BigDecimal("100.00"), 2, baseId, 1),
                        new NewOrderLine(comboId, "Combo x3", true, new BigDecimal("250.50"), 1, baseId, 3)));
    }

    private long persist(Order order) {
        return tx.execute(status -> orders.saveAndFlush(order).id());
    }

    private Order reload(long id) {
        em.clear();
        return tx.execute(status -> {
            Order order = orders.findById(id).orElseThrow();
            order.items().size();
            return order;
        });
    }

    @Test
    void anOrderWithTwoItemsRoundTripsEveryColumn() {
        UUID key = UUID.randomUUID();
        long id = persist(Order.place(newOrder(key, RESERVED_UNTIL), CREATED));

        Order loaded = reload(id);

        assertThat(loaded.orderNumber()).isEqualTo("CETO-000001");
        assertThat(loaded.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(loaded.idempotencyKey()).isEqualTo(key);
        assertThat(loaded.requestFingerprint()).isEqualTo("b".repeat(64));
        assertThat(loaded.buyerName()).isEqualTo("Buyer Name");
        assertThat(loaded.buyerPhoneE164()).isEqualTo("+5493515550000");
        assertThat(loaded.shippingAddress()).isEqualTo("Street 1");
        assertThat(loaded.shippingZone()).isEqualTo(ShippingZone.RESTO_DEL_PAIS);
        assertThat(loaded.total()).isEqualTo(new BigDecimal("450.50"));
        assertThat(loaded.reservedUntil()).isEqualTo(RESERVED_UNTIL);
        assertThat(loaded.createdAt()).isEqualTo(CREATED);
        assertThat(loaded.updatedAt()).isEqualTo(CREATED);
        assertThat(loaded.paidAt()).isNull();
        assertThat(loaded.cancelledAt()).isNull();
        assertThat(loaded.version()).isZero();
        assertThat(loaded.items()).hasSize(2);
        OrderItem first = loaded.items().get(0);
        OrderItem second = loaded.items().get(1);
        assertThat(first.lineNo()).isEqualTo((short) 1);
        assertThat(first.productName()).isEqualTo("Base");
        assertThat(first.combo()).isFalse();
        assertThat(first.unitPrice()).isEqualTo(new BigDecimal("100.00"));
        assertThat(first.lineTotal()).isEqualTo(new BigDecimal("200.00"));
        assertThat(first.baseUnitsTotal()).isEqualTo(2);
        assertThat(second.lineNo()).isEqualTo((short) 2);
        assertThat(second.productId()).isEqualTo(comboId);
        assertThat(second.baseProductId()).isEqualTo(baseId);
        assertThat(second.combo()).isTrue();
        assertThat(second.baseUnitsPerUnit()).isEqualTo(3);
        assertThat(second.baseUnitsTotal()).isEqualTo(3);
        assertThat(second.lineTotal()).isEqualTo(new BigDecimal("250.50"));
    }

    /** One lifecycle step and the status Postgres must hold right after it is committed. */
    private record Step(OrderStatus statusAfter, Consumer<Order> action) {}

    private static Step step(OrderStatus statusAfter, Consumer<Order> action) {
        return new Step(statusAfter, action);
    }

    /**
     * Applies each step in its own transaction (flush, then commit), so every intermediate state is written to
     * Postgres and checked by the V3 CHECKs, not only the final one. After each step the stored status is read
     * back with plain SQL and the version must have advanced by exactly one.
     */
    private void walk(List<Step> steps, Consumer<Order> assertions) {
        long id = persist(Order.place(newOrder(UUID.randomUUID(), RESERVED_UNTIL), CREATED));
        long expectedVersion = 0;
        for (Step step : steps) {
            tx.executeWithoutResult(status -> {
                Order order = orders.findById(id).orElseThrow();
                step.action().accept(order);
                em.flush();
                em.clear();
            });
            expectedVersion++;

            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id))
                    .isEqualTo(step.statusAfter().name());
            assertThat(jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, id))
                    .isEqualTo(expectedVersion);
        }

        Order after = reload(id);

        assertions.accept(after);
        assertThat(after.reservedUntil()).isEqualTo(RESERVED_UNTIL);
        assertThat(after.version()).isEqualTo(expectedVersion);
    }

    @Test
    void happyPathIsPersistedAndSatisfiesEveryCheck() {
        walk(List.of(
                step(OrderStatus.PAGADO, order -> order.markPaid(LATER)),
                step(OrderStatus.DESPACHADO, order -> order.dispatch(LATER.plusSeconds(60))),
                step(OrderStatus.ENTREGADO, order -> order.deliver(LATER.plusSeconds(120)))), order -> {
            assertThat(order.status()).isEqualTo(OrderStatus.ENTREGADO);
            assertThat(order.paidAt()).isEqualTo(LATER);
            assertThat(order.dispatchedAt()).isEqualTo(LATER.plusSeconds(60));
            assertThat(order.deliveredAt()).isEqualTo(LATER.plusSeconds(120));
            assertThat(order.cancelReason()).isNull();
        });
    }

    @Test
    void payingAfterReservedUntilIsPersisted() {
        Instant afterDeadline = RESERVED_UNTIL.plusSeconds(30);
        walk(List.of(step(OrderStatus.PAGADO, order -> order.markPaid(afterDeadline))), order -> {
            assertThat(order.status()).isEqualTo(OrderStatus.PAGADO);
            assertThat(order.paidAt()).isEqualTo(afterDeadline);
        });
    }

    @ParameterizedTest
    @EnumSource(value = CancelReason.class, names = {"CANCELLED_BY_ADMIN", "CANCELLED_BY_BUYER"})
    void cancellingAPendingOrderIsPersistedWithItsPair(CancelReason reason) {
        walk(List.of(step(OrderStatus.CANCELADO, order -> order.cancel(reason, LATER))), order -> {
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELADO);
            assertThat(order.cancelReason()).isEqualTo(reason);
            assertThat(order.cancelledAt()).isEqualTo(LATER);
            assertThat(order.paidAt()).isNull();
        });
    }

    @Test
    void cancellingAPaidOrderKeepsPaidAtAndSetsThePair() {
        walk(List.of(
                step(OrderStatus.PAGADO, order -> order.markPaid(LATER)),
                step(OrderStatus.CANCELADO,
                        order -> order.cancel(CancelReason.CANCELLED_BY_ADMIN, LATER.plusSeconds(5)))), order -> {
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELADO);
            assertThat(order.paidAt()).isEqualTo(LATER);
            assertThat(order.cancelledAt()).isEqualTo(LATER.plusSeconds(5));
        });
    }

    @Test
    void expiringAPendingOrderIsPersistedAsUnpaidExpired() {
        walk(List.of(step(OrderStatus.CANCELADO, order -> order.expire(RESERVED_UNTIL))), order -> {
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELADO);
            assertThat(order.cancelReason()).isEqualTo(CancelReason.EXPIRED);
            assertThat(order.cancelledAt()).isEqualTo(RESERVED_UNTIL);
            assertThat(order.paidAt()).isNull();
        });
    }

    @Test
    void orderItemsAreImmutableOnceStored() throws Exception {
        long id = persist(Order.place(newOrder(UUID.randomUUID(), RESERVED_UNTIL), CREATED));

        tx.executeWithoutResult(status -> {
            Order order = orders.findById(id).orElseThrow();
            OrderItem item = order.items().get(0);
            try {
                Field name = OrderItem.class.getDeclaredField("productName");
                name.setAccessible(true);
                name.set(item, "tampered");
                Field price = OrderItem.class.getDeclaredField("unitPrice");
                price.setAccessible(true);
                price.set(item, new BigDecimal("1.00"));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            em.flush();
        });

        assertThat(jdbc.queryForObject(
                "SELECT product_name FROM order_items WHERE order_id = ? AND line_no = 1", String.class, id))
                .isEqualTo("Base");
        assertThat(jdbc.queryForObject(
                "SELECT unit_price FROM order_items WHERE order_id = ? AND line_no = 1", BigDecimal.class, id))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void duplicateIdempotencyKeyFailsWithTheNamedConstraint() {
        UUID key = UUID.randomUUID();
        persist(Order.place(newOrder(key, RESERVED_UNTIL), CREATED));

        assertThatThrownBy(() -> persist(Order.place(newOrder(key, RESERVED_UNTIL), CREATED)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .satisfies(error -> {
                    Throwable cause = error;
                    while (cause != null && !(cause instanceof ConstraintViolationException)) {
                        cause = cause.getCause();
                    }
                    assertThat(cause).isNotNull();
                    assertThat(((ConstraintViolationException) cause).getConstraintName())
                            .isEqualTo("orders_idempotency_key_uk");
                });
    }

    @Test
    void findByIdempotencyKeyReturnsTheOrderOrNothing() {
        UUID key = UUID.randomUUID();
        long id = persist(Order.place(newOrder(key, RESERVED_UNTIL), CREATED));

        assertThat(orders.findByIdempotencyKey(key)).get().extracting(Order::id).isEqualTo(id);
        assertThat(orders.findByIdempotencyKey(UUID.randomUUID())).isEmpty();
    }

    @Test
    void lockQueriesWithoutATransactionAreRejected() {
        assertThatThrownBy(() -> orders.lockById(1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> orders.lockExpiredBatch(CREATED, 10))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> orders.nextOrderNumber()).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lockQueriesInsideATransactionStillWork() {
        long id = persist(Order.place(newOrder(UUID.randomUUID(), RESERVED_UNTIL), CREATED));

        tx.executeWithoutResult(status -> {
            assertThat(orders.lockById(id)).isPresent();
            assertThat(orders.lockExpiredBatch(RESERVED_UNTIL, 10)).extracting(Order::id).containsExactly(id);
        });
    }

    @Test
    void nextOrderNumberDrawsFromTheSequence() {
        assertThat(nextNumberInTx()).isEqualTo(1L);
        assertThat(nextNumberInTx()).isEqualTo(2L);
    }

    private long nextNumberInTx() {
        Long number = tx.execute(status -> orders.nextOrderNumber());
        return number;
    }
}
