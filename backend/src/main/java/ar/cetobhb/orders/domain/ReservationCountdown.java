package ar.cetobhb.orders.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Seconds left on an unpaid reservation, as the admin panel shows it (AOR-03). */
public final class ReservationCountdown {

    private ReservationCountdown() {
    }

    /**
     * Whole seconds (floored) until {@code reservedUntil} for a PAGO_PENDIENTE order, never negative: 0 at the
     * boundary instant and when the deadline has passed but the expiry job has not run yet (the order stays payable,
     * D-20261007-24). Null for every other status, which has no reservation to count down.
     */
    public static Long secondsLeft(OrderStatus status, Instant reservedUntil, Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reservedUntil, "reservedUntil");
        Objects.requireNonNull(now, "now");
        if (status != OrderStatus.PAGO_PENDIENTE) {
            return null;
        }
        Duration left = Duration.between(now, reservedUntil);
        return left.isNegative() ? 0L : left.getSeconds();
    }
}
