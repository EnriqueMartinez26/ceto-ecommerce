package ar.cetobhb.admin.security;

import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Scopes Spring Security's servlet filter to the administration area (design AD-A2, REQ-AUTH-06).
 *
 * <p>Boot registers the {@code springSecurityFilterChain} filter for {@code /*}. Left alone, every public request
 * would go through Security's firewall, which rejects {@code ;}, {@code //} and {@code /./} in the path and so would
 * change {@code CheckoutPathLimitHttpIT}, and through its default header writers, which add {@code X-Frame-Options},
 * {@code X-XSS-Protection} and {@code Cache-Control} to the storefront. Mapping the filter to {@code /api/admin/*}
 * keeps the public API outside Security by construction, with no exemption list to maintain.
 *
 * <p>The registration is found by type and by the name of the filter it targets, never by its bean name. The
 * post-processor is {@code static} so that it exists before the configuration classes are instantiated.
 */
@Configuration(proxyBeanMethods = false)
class SecurityFilterScopeConfig {

    /** The one URL pattern the Security filter answers to. */
    static final String ADMIN_FILTER_PATTERN = "/api/admin/*";

    /** The filter name Spring Security uses for its chain (AbstractSecurityWebApplicationInitializer). */
    static final String SECURITY_FILTER_NAME = "springSecurityFilterChain";

    @Bean
    static BeanPostProcessor securityFilterScope() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DelegatingFilterProxyRegistrationBean registration
                        && SECURITY_FILTER_NAME.equals(registration.getFilterName())) {
                    registration.setUrlPatterns(List.of(ADMIN_FILTER_PATTERN));
                }
                return bean;
            }
        };
    }
}
