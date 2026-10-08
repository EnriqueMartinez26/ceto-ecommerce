package ar.cetobhb.admin.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Admin settings, fail-closed like {@code OrderProperties} and {@code WebProperties}: every number has a minimum AND a
 * maximum and a missing key binds to 0, which violates the minimum and stops the startup. Defaults live in
 * application.yml, except the credential, which has none.
 *
 * <p>{@code username} and {@code passwordHash} are plain strings with NO Bean Validation on purpose (design AD-A4):
 * Boot's bind failure report prints the offending value, and a rejected {@code {noop}secret} would land in the startup
 * log. They are checked by {@code AdminCredentials.parse}, whose messages are fixed. {@link #toString()} redacts both.
 *
 * @param username                   the one administrator account, from {@code ADMIN_USERNAME}
 * @param passwordHash               its {@code {bcrypt}} hash, from {@code ADMIN_PASSWORD_HASH}
 * @param sessionIdleTimeoutMinutes  idle time after which an admin session ends
 * @param loginRateLimit             per-client limiter of the login endpoint
 * @param maxListedProducts          largest admin product list and stock view
 * @param maxReconciliationRows      largest reconciliation result
 */
@ConfigurationProperties("ceto.admin")
@Validated
public record AdminProperties(
        String username,
        String passwordHash,
        @Min(5) @Max(1440) int sessionIdleTimeoutMinutes,
        @NotNull @Valid LoginRateLimit loginRateLimit,
        @Min(1) @Max(500) int maxListedProducts,
        @Min(1) @Max(1000) int maxReconciliationRows) {

    /**
     * @param burst                 login attempts one client may make at once
     * @param refillIntervalSeconds seconds to earn one more attempt (90 = 40 per hour)
     * @param maxTrackedClients     clients tracked individually; beyond that new clients share one overflow bucket
     * @param overflowBurst         attempts the shared overflow bucket allows at once
     * @param overflowRefillPerHour attempts per hour the overflow bucket earns back
     */
    public record LoginRateLimit(
            @Min(1) @Max(20) int burst,
            @Min(30) @Max(3_600) int refillIntervalSeconds,
            @Min(100) @Max(100_000) int maxTrackedClients,
            @Min(1) @Max(1_000) int overflowBurst,
            @Min(1) @Max(3_600) int overflowRefillPerHour) {

        private static final long MILLIS_PER_HOUR = 3_600_000L;

        /** Time between overflow tokens, rounded down to the millisecond. */
        public Duration overflowRefillInterval() {
            return Duration.ofMillis(MILLIS_PER_HOUR / overflowRefillPerHour);
        }
    }

    /** Never prints the account name or its hash: this object can end up in a log or a failure report. */
    @Override
    public String toString() {
        return "AdminProperties[username=<redacted>, passwordHash=<redacted>, sessionIdleTimeoutMinutes="
                + sessionIdleTimeoutMinutes + ", loginRateLimit=" + loginRateLimit + ", maxListedProducts="
                + maxListedProducts + ", maxReconciliationRows=" + maxReconciliationRows + "]";
    }
}
