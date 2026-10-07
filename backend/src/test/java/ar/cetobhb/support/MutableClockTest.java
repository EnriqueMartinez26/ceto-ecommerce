package ar.cetobhb.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The test clock truncates to microseconds like the production clock and Postgres timestamptz. */
class MutableClockTest {

    @Test
    void setTruncatesToMicroseconds() {
        MutableClock clock = new MutableClock();

        clock.set(Instant.parse("2026-03-10T12:00:00.123456789Z"));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-03-10T12:00:00.123456Z"));
        assertThat(clock.instant().getNano() % 1_000).isZero();
    }

    @Test
    void resetGoesBackToTheStartInstant() {
        MutableClock clock = new MutableClock();
        clock.set(Instant.parse("2027-01-01T00:00:00.5Z"));

        clock.reset();

        assertThat(clock.instant()).isEqualTo(MutableClock.START);
    }
}
