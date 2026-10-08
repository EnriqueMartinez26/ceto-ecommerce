package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.support.AbstractHttpIT;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Spike U-3 on MockMvc (REQ-AUTH-06): the Spring Security servlet filter is registered for {@code /api/admin/*} only,
 * and {@code @AutoConfigureMockMvc} honours that mapping, so a public request never enters
 * {@code FilterChainProxy} while an admin request does. The probe is the attribute that
 * {@code CsrfFilter} leaves on the request (it is never removed, unlike the marker of the context filter), so it is
 * present only on requests that went through the chain.
 */
class SecurityFilterScopeIT extends AbstractHttpIT {

    /** The CSRF token attributes CsrfFilter always sets, deferred or not. */
    private static final List<String> CHAIN_MARKERS = List.of("_csrf", "org.springframework.security.web.csrf.CsrfToken");

    private static boolean reachedTheChain(MvcResult result) {
        return CHAIN_MARKERS.stream().anyMatch(name -> result.getRequest().getAttribute(name) != null);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void theSecurityFilterRegistrationIsMappedToTheAdminPathOnly() {
        List<DelegatingFilterProxyRegistrationBean> securityRegistrations = context
                .getBeansOfType(DelegatingFilterProxyRegistrationBean.class).values().stream()
                .filter(r -> "springSecurityFilterChain".equals(r.getFilterName())).toList();

        assertThat(securityRegistrations).hasSize(1);
        assertThat(securityRegistrations.get(0).getUrlPatterns()).containsExactly("/api/admin/*");
    }

    /**
     * The dispatcher types the Security filter is registered for, recorded from the real configuration with
     * {@code determineDispatcherTypes()} (what Boot passes to the container): ALL five, REQUEST, ASYNC, ERROR, FORWARD
     * and INCLUDE. So a forward or include whose target lies under {@code /api/admin/*} still goes through the chain;
     * the mapping is the same URL pattern for every dispatch. If a Boot upgrade narrows this set the test fails and the
     * consequence has to be reviewed. The rule that no controller forwards or includes
     * ({@code ArchitectureTest.noForwardOrInclude}) is the second line.
     */
    @Test
    void theSecurityFilterRegistrationDispatchesOnEveryDispatcherType() {
        DelegatingFilterProxyRegistrationBean registration = context
                .getBeansOfType(DelegatingFilterProxyRegistrationBean.class).values().stream()
                .filter(r -> "springSecurityFilterChain".equals(r.getFilterName())).findFirst().orElseThrow();

        assertThat(registration.determineDispatcherTypes()).containsExactlyInAnyOrder(DispatcherType.REQUEST,
                DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD, DispatcherType.INCLUDE);
    }

    @Test
    void publicRequestsNeverEnterTheSecurityFilterChain() throws Exception {
        MvcResult list = sendForResult(get("/api/store/products"));
        MvcResult missing = sendForResult(get("/api/store/products/999999"));
        MvcResult checkout = sendForResult(post("/api/checkout/orders").contentType("application/json")
                .content("{}"));

        for (MvcResult result : List.of(list, missing, checkout)) {
            assertThat(reachedTheChain(result)).as(result.getRequest().getRequestURI()).isFalse();
        }
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(checkout.getResponse().getStatus()).isEqualTo(400);
    }

    /**
     * A filter mapped wider than the admin area would still leave a public request chain-less (no chain matches it),
     * so the marker above cannot see it; Security's firewall, which sits in front of chain selection, can: it rejects
     * the matrix-parameter form that the public API has always accepted.
     */
    @Test
    void theFirewallNeverSeesAPublicPathWithAMatrixParameter() throws Exception {
        MvcResult result = sendForResult(get("/api/store/products;x=1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void adminRequestsEnterTheChainAndAnonymousOnesGet401WithoutASessionCookie() throws Exception {
        MvcResult result = sendForResult(get("/api/admin/anything"));

        MockHttpServletResponse response = result.getResponse();
        assertThat(reachedTheChain(result)).isTrue();
        assertThat(response.getStatus()).isEqualTo(401);
        // csrf.spa() renders the XSRF-TOKEN cookie on every admin response; a server session cookie never appears.
        assertThat(response.getHeaders("Set-Cookie")).allMatch(cookie -> cookie.startsWith("XSRF-TOKEN="));
    }
}
