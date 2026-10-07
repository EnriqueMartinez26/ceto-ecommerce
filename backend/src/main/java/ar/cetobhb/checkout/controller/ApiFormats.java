package ar.cetobhb.checkout.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Wire formats (D-20261007-16): money is a decimal string with 2 decimals, timestamps are ISO-8601 UTC in seconds. */
final class ApiFormats {

    private ApiFormats() {
    }

    static String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2).toPlainString();
    }

    static String instant(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }
}
