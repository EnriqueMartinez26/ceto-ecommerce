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

    private final TokenBucketLimiter checkoutLimiter;

    /**
     * Also checks what Tomcat will really use: the yml wires {@code server.tomcat.remoteip.internal-proxies} to the
     * validated {@code ceto.web.trusted-proxies}, but an environment variable or command-line argument can override it
     * directly (REQ-ABU-03), and the "framework" forward-headers strategy would believe any caller.
     */
    WebConfig(Clock clock, WebProperties web, Environment environment) {
        String proxies = environment.getProperty(INTERNAL_PROXIES);
        if (!TrustedProxies.isSafe(proxies)) {
            throw new IllegalStateException(INTERNAL_PROXIES + " is missing or trusts public addresses");
        }
        if (!"native".equalsIgnoreCase(environment.getProperty(FORWARD_HEADERS))) {
            throw new IllegalStateException(FORWARD_HEADERS + " must be native");
        }
        this.checkoutLimiter = checkoutLimiter(clock, web.checkoutRateLimit());
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
