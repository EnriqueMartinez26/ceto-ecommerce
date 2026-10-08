package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReservationCountdownTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PAGO_PENDIENTE", mode = EnumSource.Mode.EXCLUDE)
    void isNullForEveryStatusButPending(OrderStatus status) {
        assertThat(ReservationCountdown.secondsLeft(status, NOW.plusSeconds(3600), NOW)).isNull();
    }

    @Test
    void countsTheWholeSecondsLeftOfAPendingOrder() {
        assertThat(ReservationCountdown.secondsLeft(OrderStatus.PAGO_PENDIENTE, NOW.plusSeconds(90), NOW))
                .isEqualTo(90L);
    }

    @Test
    void floorsPartialSeconds() {
        assertThat(ReservationCountdown.secondsLeft(OrderStatus.PAGO_PENDIENTE, NOW.plusMillis(1999), NOW))
                .isEqualTo(1L);
        assertThat(ReservationCountdown.secondsLeft(OrderStatus.PAGO_PENDIENTE, NOW.plusMillis(999), NOW))
                .isZero();
    }

    @Test
    void theBoundaryInstantIsZero() {
        assertThat(ReservationCountdown.secondsLeft(OrderStatus.PAGO_PENDIENTE, NOW, NOW)).isZero();
    }

    @Test
    void anOverdueReservationIsZeroNeverNegative() {
        assertThat(ReservationCountdown.secondsLeft(OrderStatus.PAGO_PENDIENTE, NOW.minusSeconds(1), NOW)).isZero();
        assertThat(ReservationCountdown.secondsLeft(OrderStatus.PAGO_PENDIENTE, NOW.minusMillis(1), NOW)).isZero();
        assertThat(ReservationCountdown.secondsLeft(OrderStatus.PAGO_PENDIENTE, NOW.minusSeconds(100_000), NOW))
                .isZero();
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> ReservationCountdown.secondsLeft(null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationCountdown.secondsLeft(OrderStatus.PAGADO, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationCountdown.secondsLeft(OrderStatus.PAGADO, NOW, null))
                .isInstanceOf(NullPointerException.class);
    }
}
