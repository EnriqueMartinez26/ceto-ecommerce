package ar.cetobhb.admin.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Admin settings, fail-closed like {@code OrderProperties} and {@code WebProperties}: every number has a minimum AND a
 * maximum and a missing key binds to 0, which violates the minimum and stops the startup. Defaults live in
 * application.yml.
 *
 * @param maxListedProducts      largest admin product list and stock view
 * @param maxReconciliationRows  largest reconciliation result
 */
@ConfigurationProperties("ceto.admin")
@Validated
public record AdminProperties(
        @Min(1) @Max(500) int maxListedProducts,
        @Min(1) @Max(1000) int maxReconciliationRows) {
}
