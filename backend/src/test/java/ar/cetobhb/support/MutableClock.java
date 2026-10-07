package ar.cetobhb.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only clock whose instant can be set and advanced. Always UTC (CLAUDE.md rule 21). */
public final class MutableClock extends Clock {

    /** The instant every test starts from. */
    public static final Instant START = Instant.parse("2026-03-10T12:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    public void set(Instant instant) {
        now.set(instant);
    }

    public void reset() {
        now.set(START);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new UnsupportedOperationException("the test clock is always UTC");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
