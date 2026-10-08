package ar.cetobhb.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Web settings, fail-closed like {@code OrderProperties}: every number has a minimum AND a maximum, and a missing or
 * unsafe value stops the startup. Defaults live in application.yml, except {@code trustedProxies}, which has none.
 *
 * @param maxBodyBytes         largest accepted request body (checked on the declared length and while streaming)
 * @param storefrontMaxProducts largest storefront list
 * @param trustedProxies        Tomcat {@code internal-proxies} value: the peers allowed to set the client address
 *                              through {@code X-Forwarded-For}. A comma-separated CIDR list whose every entry lies
 *                              inside a private range (see {@link TrustedProxies}). Required, from
 *                              {@code TRUSTED_PROXIES}
 * @param checkoutRateLimit     per-client limiter of {@code POST /api/checkout/orders}
 */
@ConfigurationProperties("ceto.web")
@Validated
public record WebProperties(
        @Min(1024) @Max(262_144) int maxBodyBytes,
        @Min(1) @Max(500) int storefrontMaxProducts,
        @NotBlank String trustedProxies,
        @NotNull @Valid CheckoutRateLimit checkoutRateLimit) {

    /**
     * @param burst              attempts one client may make at once
     * @param refillIntervalSeconds seconds to earn one more attempt (360 = 10 per hour)
     * @param maxTrackedClients  clients tracked individually; beyond that new clients share one overflow bucket
     * @param overflowBurst      attempts the shared overflow bucket allows at once; deliberately larger than one
     *                           client's burst, because it serves MANY clients when the table is full
     * @param overflowRefillPerHour attempts per hour the overflow bucket earns back (100 = one every 36 seconds)
     */
    public record CheckoutRateLimit(
            @Min(1) @Max(100) int burst,
            @Min(1) @Max(86_400) int refillIntervalSeconds,
            @Min(100) @Max(100_000) int maxTrackedClients,
            @Min(1) @Max(10_000) int overflowBurst,
            @Min(1) @Max(3_600) int overflowRefillPerHour) {

        private static final long MILLIS_PER_HOUR = 3_600_000L;

        /** Time between overflow tokens, rounded down to the millisecond. */
        public Duration overflowRefillInterval() {
            return Duration.ofMillis(MILLIS_PER_HOUR / overflowRefillPerHour);
        }
    }

    /** Rejects a value that is not a CIDR list confined to the private ranges. */
    @AssertTrue(message = "trusted-proxies must be a CIDR list whose every entry lies inside a private range")
    public boolean isTrustedProxiesSafe() {
        return TrustedProxies.isSafe(trustedProxies);
    }
}
