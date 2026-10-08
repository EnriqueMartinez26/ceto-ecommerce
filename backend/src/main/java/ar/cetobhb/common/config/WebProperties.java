package ar.cetobhb.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Web settings, fail-closed like {@code OrderProperties}: every number has a minimum AND a maximum, and a missing or
 * unsafe value stops the startup. Defaults live in application.yml, except {@code trustedProxies}, which has none.
 *
 * @param storefrontMaxProducts largest storefront list
 * @param trustedProxies        Tomcat {@code internal-proxies} value: the peers allowed to set the client address
 *                              through {@code X-Forwarded-For}. A CIDR list when it contains {@code /}, otherwise one
 *                              full-match regex (see {@link TrustedProxies}). Required, from {@code TRUSTED_PROXIES}
 * @param checkoutRateLimit     per-client limiter of {@code POST /api/checkout/orders}
 */
@ConfigurationProperties("ceto.web")
@Validated
public record WebProperties(
        @Min(1) @Max(500) int storefrontMaxProducts,
        @NotBlank String trustedProxies,
        @NotNull @Valid CheckoutRateLimit checkoutRateLimit) {

    /**
     * @param burst              attempts one client may make at once
     * @param refillIntervalSeconds seconds to earn one more attempt (360 = 10 per hour)
     * @param maxTrackedClients  clients tracked individually; beyond that new clients share one bucket
     */
    public record CheckoutRateLimit(
            @Min(1) @Max(100) int burst,
            @Min(1) @Max(86_400) int refillIntervalSeconds,
            @Min(100) @Max(100_000) int maxTrackedClients) {
    }

    /** Rejects a value that parses as nothing or that would let a public address set the client address. */
    @AssertTrue(message = "trusted-proxies must be a CIDR list or a regex that matches private addresses only")
    public boolean isTrustedProxiesSafe() {
        return TrustedProxies.isSafe(trustedProxies);
    }
}
