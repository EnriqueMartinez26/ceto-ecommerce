package ar.cetobhb.orders.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Order limits and reservation expiry. Fail-closed: every value has a minimum AND a maximum, a missing or
 * out-of-range value stops the startup (a missing int key binds to 0, which violates the minimum; a missing
 * expiry block is rejected by {@code @NotNull}). Defaults live in application.yml.
 *
 * @param reservationHours   hours a PAGO_PENDIENTE order keeps its reservation (D-20261006-09)
 * @param maxUnitsPerLine    maximum quantity of one line (D-20261007-02)
 * @param maxLinesPerOrder   maximum number of lines per order (D-20261007-05)
 * @param expiry             settings of the expiry job
 */
@ConfigurationProperties("ceto.orders")
@Validated
public record OrderProperties(
        @Min(1) @Max(168) int reservationHours,
        @Min(1) @Max(100) int maxUnitsPerLine,
        @Min(1) @Max(50) int maxLinesPerOrder,
        @NotNull @Valid Expiry expiry) {

    /**
     * @param batchSize    orders expired per job run
     * @param fixedDelayMs pause between job runs, in milliseconds
     */
    public record Expiry(
            @Min(1) @Max(1000) int batchSize,
            @Min(1000) @Max(3_600_000) long fixedDelayMs) {
    }
}
