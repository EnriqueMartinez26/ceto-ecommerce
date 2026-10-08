package adminprobe;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only wiring for the admin probe contexts: registers {@link AdminProbeController} and stamps every request that
 * enters the administration chain with a marker, so "this request went through the admin chain" no longer depends on
 * an attribute that some other filter happens to leave behind. The marker filter is put first in the chain by wrapping
 * the chain bean, so production code carries no test seam.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(AdminProbeController.class)
public class AdminProbeTestConfig {

    /** Request attribute set by the first filter of the administration chain. */
    public static final String CHAIN_ATTRIBUTE = "adminprobe.enteredAdminChain";

    /** Response header with the same meaning, for tests that only see the HTTP response (real server). */
    public static final String CHAIN_HEADER = "X-Test-Entered-Admin-Chain";

    /** Bean name of the chain built by AdminSecurityChainConfig. */
    private static final String ADMIN_CHAIN_BEAN = "adminSecurityFilterChain";

    @Bean
    static BeanPostProcessor adminChainMarker() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (ADMIN_CHAIN_BEAN.equals(beanName) && bean instanceof DefaultSecurityFilterChain chain) {
                    List<Filter> filters = new ArrayList<>();
                    filters.add((request, response, next) -> {
                        request.setAttribute(CHAIN_ATTRIBUTE, Boolean.TRUE);
                        ((HttpServletResponse) response).setHeader(CHAIN_HEADER, "true");
                        next.doFilter(request, response);
                    });
                    filters.addAll(chain.getFilters());
                    SecurityFilterChain marked = new DefaultSecurityFilterChain(chain.getRequestMatcher(), filters);
                    return marked;
                }
                return bean;
            }
        };
    }
}
