package archfixtures.ar.cetobhb.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Deliberate violations: every banned way of reading the system clock or zone. Each method holds exactly
 * one violation and has a unique name so the test can assert every one of them individually.
 */
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

    Object localDateNowWithZone(ZoneId zone) {
        return LocalDate.now(zone);
    }

    Object localTimeNow() {
        return LocalTime.now();
    }

    Object zonedDateTimeNow() {
        return ZonedDateTime.now();
    }

    Object offsetDateTimeNow() {
        return OffsetDateTime.now();
    }

    Object yearNow() {
        return Year.now();
    }

    Object yearMonthNow() {
        return YearMonth.now();
    }

    Object monthDayNow() {
        return MonthDay.now();
    }

    Object clockSystemUtc() {
        return Clock.systemUTC();
    }

    Object clockSystemWithZone(ZoneId zone) {
        return Clock.system(zone);
    }

    Object clockSystemDefaultZone() {
        return Clock.systemDefaultZone();
    }

    Object zoneIdSystemDefault() {
        return ZoneId.systemDefault();
    }

    long systemCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    Object newDate() {
        return new Date();
    }

    Object offsetDateTimeNowWithZoneOffset() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    Object zonedDateTimeNowWithZone(ZoneId zone) {
        return ZonedDateTime.now(zone);
    }

    Object timeZoneGetDefault() {
        return TimeZone.getDefault();
    }

    Object newGregorianCalendar() {
        return new GregorianCalendar();
    }

    Object calendarGetInstance() {
        return Calendar.getInstance();
    }
}
