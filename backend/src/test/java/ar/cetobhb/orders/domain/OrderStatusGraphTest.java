package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusGraphTest {

    private record Edge(OrderStatus from, OrderStatus to) {}

    /** Declared independently of the enum on purpose: D-20261006-07. */
    private static final Set<Edge> EXPECTED_EDGES = Set.of(
            new Edge(OrderStatus.PAGO_PENDIENTE, OrderStatus.PAGADO),
            new Edge(OrderStatus.PAGADO, OrderStatus.DESPACHADO),
            new Edge(OrderStatus.DESPACHADO, OrderStatus.ENTREGADO),
            new Edge(OrderStatus.PAGO_PENDIENTE, OrderStatus.CANCELADO),
            new Edge(OrderStatus.PAGADO, OrderStatus.CANCELADO));

    static Stream<Arguments> allPairs() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void onlyTheFiveExpectedEdgesAreAllowed(OrderStatus from, OrderStatus to) {
        if (EXPECTED_EDGES.contains(new Edge(from, to))) {
            assertThat(from.allowedTargets()).contains(to);
            assertThat(from.requireTransitionTo(to)).isEqualTo(to);
        } else {
            assertThat(from.allowedTargets()).doesNotContain(to);
            assertThatThrownBy(() -> from.requireTransitionTo(to))
                    .isInstanceOfSatisfying(IllegalOrderTransitionException.class, e -> {
                        assertThat(e.from()).isEqualTo(from);
                        assertThat(e.to()).isEqualTo(to);
                        assertThat(e.getMessage()).isEqualTo("ILLEGAL_TRANSITION(" + from + "->" + to + ")");
                    });
        }
    }

    @Test
    void pairSpaceIsTwentyFiveWithFiveAllowed() {
        assertThat(allPairs().count()).isEqualTo(25);
        assertThat(EXPECTED_EDGES).hasSize(5);
    }

    @Test
    void terminalStatesAbsorb() {
        assertThat(OrderStatus.ENTREGADO.allowedTargets()).isEmpty();
        assertThat(OrderStatus.CANCELADO.allowedTargets()).isEmpty();
    }

    @Test
    void thereIsNoNuevoStatus() {
        assertThat(Arrays.stream(OrderStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("PAGO_PENDIENTE", "PAGADO", "DESPACHADO", "ENTREGADO", "CANCELADO");
    }

    @Test
    void allowedTargetsIsNotAMutableView() {
        Set<OrderStatus> targets = OrderStatus.PAGO_PENDIENTE.allowedTargets();
        targets.clear();
        assertThat(OrderStatus.PAGO_PENDIENTE.allowedTargets()).hasSize(2);
    }

    static Stream<Arguments> cancelMatrix() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(CancelReason.values()).map(r -> Arguments.of(from, r)));
    }

    @ParameterizedTest(name = "cancel {0} with {1}")
    @MethodSource("cancelMatrix")
    void cancelReasonValidityPerSourceState(OrderStatus from, CancelReason reason) {
        boolean sourceCancellable = from == OrderStatus.PAGO_PENDIENTE || from == OrderStatus.PAGADO;
        boolean expired = reason == CancelReason.EXPIRED;
        if (!sourceCancellable) {
            // The edge is checked first: the reason is irrelevant when the transition does not exist.
            assertThatThrownBy(() -> from.requireCancellation(reason))
                    .isInstanceOf(IllegalOrderTransitionException.class);
        } else if (expired && from == OrderStatus.PAGADO) {
            // D-20261007-10: PAGADO -> CANCELADO is legal, the EXPIRED reason is not.
            assertThatThrownBy(() -> from.requireCancellation(reason))
                    .isInstanceOfSatisfying(ValidationException.class,
                            e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON));
        } else {
            assertThatCode(() -> assertThat(from.requireCancellation(reason)).isEqualTo(OrderStatus.CANCELADO))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void nullCancelReasonIsAValidationError() {
        assertThatThrownBy(() -> OrderStatus.PAGO_PENDIENTE.requireCancellation(null))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON));
    }

    @ParameterizedTest(name = "null reason from {0} is a validation error")
    @EnumSource(value = OrderStatus.class, names = {"PAGO_PENDIENTE", "PAGADO"})
    void nullCancelReasonFromCancellableStateIsInvalidCancelReason(OrderStatus from) {
        assertThatThrownBy(() -> from.requireCancellation(null))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_CANCEL_REASON));
    }

    @Test
    void nullCancelReasonFromDespachadoIsAnIllegalTransitionBecauseTheEdgeIsCheckedFirst() {
        assertThatThrownBy(() -> OrderStatus.DESPACHADO.requireCancellation(null))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void shippingZonesAreTheTwoDeclaredOnes() {
        assertThat(Arrays.stream(ShippingZone.values()).map(Enum::name))
                .containsExactlyInAnyOrder("CORDOBA_CAPITAL", "RESTO_DEL_PAIS");
    }

    @Test
    void orderNotFoundCarriesTheOrderIdInItsMessage() {
        assertThat(new OrderNotFoundException(7L).getMessage()).isEqualTo("ORDER_NOT_FOUND(7)");
    }
}
