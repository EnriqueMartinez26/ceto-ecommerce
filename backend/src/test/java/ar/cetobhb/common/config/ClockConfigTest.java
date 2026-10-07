package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The production clock matches Postgres timestamptz precision (microseconds), so no nanosecond is ever lost. */
class ClockConfigTest {

    private final Clock clock = new ClockConfig().clock();

    @Test
    void ticksInWholeMicroseconds() {
        for (int i = 0; i < 500; i++) {
            Instant instant = clock.instant();
            assertThat(instant.getNano() % 1_000).as("instant %s", instant).isZero();
        }
    }

    @Test
    void staysUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
