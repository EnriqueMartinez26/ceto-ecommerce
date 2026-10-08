package ar.cetobhb.common.config;

import ar.cetobhb.common.domain.TokenBucketLimiter;
import ar.cetobhb.common.web.CheckoutRateLimitInterceptor;
import ar.cetobhb.common.web.RequestIdFilter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The public HTTP surface: servlet filter registrations (order is explicit, the request id comes first) and the
 * checkout rate limit.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    static final String CHECKOUT_PATH = "/api/checkout/orders";
    private static final String INTERNAL_PROXIES = "server.tomcat.remoteip.internal-proxies";
    private static final String FORWARD_HEADERS = "server.forward-headers-strategy";
    private static final String REMOTE_IP_HEADER = "server.tomcat.remoteip.remote-ip-header";
    private static final String TRUSTED_PROXIES = "server.tomcat.remoteip.trusted-proxies";
    private static final String PROXIES_HEADER = "server.tomcat.remoteip.proxies-header";
    private static final String HOST_HEADER = "server.tomcat.remoteip.host-header";
    private static final String PORT_HEADER = "server.tomcat.remoteip.port-header";
    private static final String HTTPS_VALUE = "server.tomcat.remoteip.protocol-header-https-value";
    private static final String PROTOCOL_HEADER = "server.tomcat.remoteip.protocol-header";
    private static final String BOOT_PROTOCOL_HEADER = "X-Forwarded-Proto";

    private final TokenBucketLimiter checkoutLimiter;

    /**
     * Also checks what Tomcat will really use: the yml wires {@code server.tomcat.remoteip.internal-proxies} to the
     * validated {@code ceto.web.trusted-proxies}, but an environment variable or command-line argument can override it
     * directly (REQ-ABU-03), and the "framework" forward-headers strategy would believe any caller. The header the
     * valve reads must stay {@code X-Forwarded-For} (the integration tests pin its right-to-left walk) and neither
     * {@code trusted-proxies} nor {@code proxies-header} may be set. {@code host-header}, {@code port-header} and
     * {@code protocol-header-https-value} must be explicitly empty, and {@code protocol-header} unset, empty or
     * {@code X-Forwarded-Proto}, so a trusted peer cannot rewrite the host or port the application sees.
     */
    WebConfig(Clock clock, WebProperties web, Environment environment) {
        String proxies = environment.getProperty(INTERNAL_PROXIES);
        if (!TrustedProxies.isSafe(proxies)) {
            throw new IllegalStateException(INTERNAL_PROXIES + " is missing or is not a CIDR list inside the private ranges");
        }
        if (!"native".equalsIgnoreCase(environment.getProperty(FORWARD_HEADERS))) {
            throw new IllegalStateException(FORWARD_HEADERS + " must be native");
        }
        if (!"X-Forwarded-For".equalsIgnoreCase(environment.getProperty(REMOTE_IP_HEADER))) {
            throw new IllegalStateException(REMOTE_IP_HEADER + " must be X-Forwarded-For");
        }
        requireBlank(environment, TRUSTED_PROXIES);
        requireBlank(environment, PROXIES_HEADER);
        requireExplicitlyBlank(environment, HOST_HEADER);
        requireExplicitlyBlank(environment, PORT_HEADER);
        requireExplicitlyBlank(environment, HTTPS_VALUE);
        String protocolHeader = environment.getProperty(PROTOCOL_HEADER);
        if (protocolHeader != null && !protocolHeader.isBlank()
                && !BOOT_PROTOCOL_HEADER.equalsIgnoreCase(protocolHeader)) {
            throw new IllegalStateException(PROTOCOL_HEADER + " must be unset or " + BOOT_PROTOCOL_HEADER);
        }
        this.checkoutLimiter = checkoutLimiter(clock, web.checkoutRateLimit());
    }

    /**
     * The valve skips every address of {@code trusted-proxies} while it walks the chain, so any value there widens the
     * trust beyond {@code internal-proxies}. Boot 4.1 has no {@code proxies-header} property (verified against
     * spring-boot-tomcat 4.1.1), so that check is a guard against a future version that adds one.
     */
    private static void requireBlank(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value != null && !value.isBlank()) {
            throw new IllegalStateException(property + " must be unset");
        }
    }

    /**
     * Boot hands {@code host-header} (default {@code X-Forwarded-Host}), {@code port-header} (default
     * {@code X-Forwarded-Port}) and {@code protocol-header-https-value} (default {@code https}) to the valve even when
     * nothing sets them, so an UNSET property is not a blank one: the yml must blank them explicitly and an override must
     * keep them blank. A trusted peer could otherwise rewrite the host, port and scheme the app sees, which is how a
     * spoofed absolute URL would reach a redirect or a link. The valve still honours {@code X-Forwarded-Proto}
     * (no Boot default for {@code protocol-header}), which cannot carry a host.
     */
    private static void requireExplicitlyBlank(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || !value.isBlank()) {
            throw new IllegalStateException(property + " must be set to an empty value");
        }
    }

    static TokenBucketLimiter checkoutLimiter(Clock clock, WebProperties.CheckoutRateLimit limit) {
        Duration refill = Duration.ofSeconds(limit.refillIntervalSeconds());
        return new TokenBucketLimiter(clock, limit.burst(), refill, limit.maxTrackedClients(),
                limit.overflowBurst(), limit.overflowRefillInterval());
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Only the POST: the storefront GETs are not limited (D-20261007-22). */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CheckoutRateLimitInterceptor(checkoutLimiter)).addPathPatterns(CHECKOUT_PATH);
    }
}
