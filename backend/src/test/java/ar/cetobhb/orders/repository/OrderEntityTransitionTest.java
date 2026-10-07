package ar.cetobhb.orders.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.NewOrder;
import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.ReservationNotExpiredException;
import ar.cetobhb.orders.domain.ShippingZone;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Entity transition rules (REQ-LIF-02, -05, -07). Plain Java: no database involved. */
class OrderEntityTransitionTest {

    private static final Instant CREATED = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant RESERVED_UNTIL = CREATED.plus(Duration.ofHours(24));
    private static final Instant LATER = CREATED.plus(Duration.ofHours(1));

    static NewOrder newOrder() {
        return new NewOrder("CETO-000001", UUID.randomUUID(), "a".repeat(64), "Buyer", "+5493515550000",
                "Street 1", ShippingZone.CORDOBA_CAPITAL, RESERVED_UNTIL, List.of(
                        new NewOrderLine(7, "Base", false, new BigDecimal("100.00"), 2, 7, 1),
                        new NewOrderLine(9, "Combo x3", true, new BigDecimal("250.50"), 1, 7, 3)));
    }

    private static Order pending() {
        return Order.place(newOrder(), CREATED);
    }

    private static Order inStatus(OrderStatus target) {
        Order order = pending();
        switch (target) {
            case PAGO_PENDIENTE -> { }
            case PAGADO -> order.markPaid(LATER);
            case DESPACHADO -> {
                order.markPaid(LATER);
                order.dispatch(LATER);
            }
            case ENTREGADO -> {
                order.markPaid(LATER);
                order.dispatch(LATER);
                order.deliver(LATER);
            }
            case CANCELADO -> order.cancel(CancelReason.CANCELLED_BY_ADMIN, LATER);
        }
        return order;
    }

    @Test
    void placeBuildsAPendingOrderWithSnapshotLinesAndServerTotals() {
        Order order = pending();

        assertThat(order.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(order.total()).isEqualByComparingTo("450.50");
        assertThat(order.reservedUntil()).isEqualTo(RESERVED_UNTIL);
        assertThat(order.createdAt()).isEqualTo(CREATED);
        assertThat(order.updatedAt()).isEqualTo(CREATED);
        assertThat(order.paidAt()).isNull();
        assertThat(order.cancelReason()).isNull();
        assertThat(order.cancelledAt()).isNull();
        assertThat(order.items()).extracting(OrderItem::lineNo).containsExactly((short) 1, (short) 2);
        assertThat(order.items().get(0).lineTotal()).isEqualByComparingTo("200.00");
        assertThat(order.items().get(1).lineTotal()).isEqualByComparingTo("250.50");
        assertThat(order.items().get(1).baseUnitsTotal()).isEqualTo(3);
    }

    @Test
    void placeRejectsAReservationThatIsNotInTheFuture() {
        NewOrder data = newOrder();
        NewOrder past = new NewOrder(data.orderNumber(), data.idempotencyKey(), data.requestFingerprint(),
                data.buyerName(), data.buyerPhoneE164(), data.shippingAddress(), data.zone(), CREATED, data.lines());

        assertThatThrownBy(() -> Order.place(past, CREATED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaidSetsStatusAndPaidAtOnly() {
        Order order = pending();

        order.markPaid(LATER);

        assertThat(order.status()).isEqualTo(OrderStatus.PAGADO);
        assertThat(order.paidAt()).isEqualTo(LATER);
        assertThat(order.updatedAt()).isEqualTo(LATER);
        assertThat(order.reservedUntil()).isEqualTo(RESERVED_UNTIL);
    }

    @Test
    void markPaidStillSucceedsOnceReservedUntilHasPassedButTheJobHasNotRun() {
        Order order = pending();
        Instant afterDeadline = RESERVED_UNTIL.plusSeconds(60);

        order.markPaid(afterDeadline);

        assertThat(order.status()).isEqualTo(OrderStatus.PAGADO);
        assertThat(order.paidAt()).isEqualTo(afterDeadline);
    }

    @Test
    void fullHappyPathStampsEachTimestampAndNeverMovesReservedUntil() {
        Order order = pending();

        order.markPaid(LATER);
        order.dispatch(LATER.plusSeconds(1));
        order.deliver(LATER.plusSeconds(2));

        assertThat(order.status()).isEqualTo(OrderStatus.ENTREGADO);
        assertThat(order.dispatchedAt()).isEqualTo(LATER.plusSeconds(1));
        assertThat(order.deliveredAt()).isEqualTo(LATER.plusSeconds(2));
        assertThat(order.reservedUntil()).isEqualTo(RESERVED_UNTIL);
    }

    @ParameterizedTest
    @EnumSource(value = CancelReason.class, names = {"CANCELLED_BY_ADMIN", "CANCELLED_BY_BUYER"})
    void cancelFromPendingOrPaidSetsReasonAndCancelledAtTogether(CancelReason reason) {
        for (OrderStatus from : List.of(OrderStatus.PAGO_PENDIENTE, OrderStatus.PAGADO)) {
            Order order = inStatus(from);
            Instant paidAt = order.paidAt();

            order.cancel(reason, LATER.plusSeconds(5));

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELADO);
            assertThat(order.cancelReason()).isEqualTo(reason);
            assertThat(order.cancelledAt()).isEqualTo(LATER.plusSeconds(5));
            assertThat(order.paidAt()).isEqualTo(paidAt);
            assertThat(order.reservedUntil()).isEqualTo(RESERVED_UNTIL);
        }
    }

    @Test
    void cancelRejectsExpiredAndNullReasonsWithoutChangingTheOrder() {
        Order order = pending();

        assertThatThrownBy(() -> order.cancel(CancelReason.EXPIRED, LATER))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON));
        assertThatThrownBy(() -> order.cancel(null, LATER))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON));

        assertThat(order.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(order.cancelReason()).isNull();
        assertThat(order.cancelledAt()).isNull();
    }

    @Test
    void expireIsInclusiveAtTheBoundaryAndSetsExpiredReasonWithoutPayment() {
        Order order = pending();

        order.expire(RESERVED_UNTIL);

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(order.cancelReason()).isEqualTo(CancelReason.EXPIRED);
        assertThat(order.cancelledAt()).isEqualTo(RESERVED_UNTIL);
        assertThat(order.paidAt()).isNull();
        assertThat(order.reservedUntil()).isEqualTo(RESERVED_UNTIL);
    }

    @Test
    void expireBeforeTheDeadlineIsRejectedAndLeavesTheOrderUntouched() {
        Order order = pending();

        assertThatThrownBy(() -> order.expire(RESERVED_UNTIL.minusNanos(1)))
                .isInstanceOf(ReservationNotExpiredException.class);

        assertThat(order.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(order.cancelReason()).isNull();
    }

    @Test
    void expireFromPaidIsAnInvalidReasonNotAnUnpaidExpiry() {
        Order order = inStatus(OrderStatus.PAGADO);

        assertThatThrownBy(() -> order.expire(RESERVED_UNTIL.plusSeconds(1)))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON));

        assertThat(order.status()).isEqualTo(OrderStatus.PAGADO);
        assertThat(order.cancelReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DESPACHADO", "ENTREGADO", "CANCELADO"})
    void expireWithoutACancelEdgeIsAnIllegalTransition(OrderStatus from) {
        Order order = inStatus(from);

        assertThatThrownBy(() -> order.expire(RESERVED_UNTIL.plusSeconds(1)))
                .isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(order.status()).isEqualTo(from);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void everyIllegalEdgeThrowsAndLeavesTheStatusAlone(OrderStatus from) {
        for (OrderStatus target : OrderStatus.values()) {
            if (target == OrderStatus.PAGO_PENDIENTE || from.allowedTargets().contains(target)) {
                continue;
            }
            Order order = inStatus(from);
            Runnable attempt = switch (target) {
                case PAGADO -> () -> order.markPaid(LATER);
                case DESPACHADO -> () -> order.dispatch(LATER);
                case ENTREGADO -> () -> order.deliver(LATER);
                case CANCELADO -> () -> order.cancel(CancelReason.CANCELLED_BY_BUYER, LATER);
                case PAGO_PENDIENTE -> throw new AssertionError("no operation re-enters PAGO_PENDIENTE");
            };

            assertThatThrownBy(attempt::run).as(from + " -> " + target)
                    .isInstanceOf(IllegalOrderTransitionException.class);
            assertThat(order.status()).isEqualTo(from);
        }
    }

    @Test
    void ordersAndItemsExposeNoPublicSetterOrGenericStatusMutator() {
        for (Class<?> type : List.of(Order.class, OrderItem.class)) {
            List<String> mutators = Arrays.stream(type.getMethods())
                    .filter(m -> !Modifier.isStatic(m.getModifiers()))
                    .filter(m -> m.getDeclaringClass() == type)
                    .filter(m -> m.getName().startsWith("set")
                            || Arrays.asList(m.getParameterTypes()).contains(OrderStatus.class))
                    .map(Method::getName)
                    .toList();

            assertThat(mutators).as(type.getSimpleName() + " public status/setter mutators").isEmpty();
        }
    }

    @Test
    void itemsViewIsUnmodifiable() {
        Order order = pending();

        assertThatThrownBy(() -> order.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
