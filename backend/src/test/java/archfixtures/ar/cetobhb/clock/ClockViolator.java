package archfixtures.ar.cetobhb.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Deliberate violations: every banned way of reading the system clock or zone. */
public class ClockViolator {

    Object instantNow() {
        return Instant.now();
    }

    Object localDateTimeNow() {
        return LocalDateTime.now();
    }

    Object localDateNow() {
        return LocalDate.now();
    }

    Object zonedDateTimeNow() {
        return ZonedDateTime.now();
    }

    Object offsetDateTimeNow() {
        return OffsetDateTime.now();
    }

    Object systemDefaultZoneClock() {
        return Clock.systemDefaultZone();
    }

    Object systemDefaultZoneId() {
        return ZoneId.systemDefault();
    }
}
