package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.web.BodySizeLimitFilter;
import ar.cetobhb.common.web.RequestIdFilter;
import ar.cetobhb.common.web.SecurityHeadersFilter;
import ar.cetobhb.support.AbstractHttpIT;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;

/**
 * The order of the servlet filters is part of the contract (REQ-HRD-12, REQ-ERR-06): a 401, a 403 or a firewall 400
 * written by Security carries the request id and the hardening headers only because the filters that set them run
 * first. The behaviour is pinned by {@code AdminSecurityChainIT} and {@code SecurityFirewallVariantsIT}; this pins the
 * numbers, so a change that moves Security earlier fails here with a clear reason.
 */
class SecurityFilterOrderIT extends AbstractHttpIT {

    @Autowired
    private ApplicationContext context;

    /** The registration beans are generic; asking the context by raw type is the only way to list them all. */
    @SuppressWarnings("rawtypes")
    private int orderOf(Class<? extends Filter> filterType) {
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .mapToInt(registration -> registration.getOrder()).findFirst().orElseThrow();
    }

    private int securityOrder() {
        return context.getBeansOfType(DelegatingFilterProxyRegistrationBean.class).values().stream()
                .filter(registration -> SecurityFilterScopeConfig.SECURITY_FILTER_NAME.equals(registration.getFilterName()))
                .mapToInt(DelegatingFilterProxyRegistrationBean::getOrder).findFirst().orElseThrow();
    }

    @Test
    void theRequestIdComesFirstThenTheHeadersThenTheBodyLimitAndSecurityLast() {
        int requestId = orderOf(RequestIdFilter.class);
        int headers = orderOf(SecurityHeadersFilter.class);
        int body = orderOf(BodySizeLimitFilter.class);
        int security = securityOrder();

        assertThat(requestId).as("request id").isLessThan(headers);
        assertThat(headers).as("hardening headers").isLessThan(body);
        assertThat(body).as("body size limit").isLessThan(security);
    }
}
