package archfixtures.ar.cetobhb.clean.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/** Compliant control: proves the rules do not fail on everything. Uses an injected clock only. */
public class CleanDomain {

    Instant at(Clock clock) {
        return clock.instant();
    }

    Instant instantNowWithClock(Clock clock) {
        return Instant.now(clock);
    }

    LocalDate localDateNowWithClock(Clock clock) {
        return LocalDate.now(clock);
    }

    LocalDate localDateNowWithZonedClock(Clock clock, ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    Date dateFromMillis(long millis) {
        return new Date(millis);
    }
}
