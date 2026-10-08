package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.support.AbstractHttpIT;
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
    void adminRequestsEnterTheChainAndAnonymousOnesGet401WithoutACookie() throws Exception {
        MvcResult result = sendForResult(get("/api/admin/anything"));

        MockHttpServletResponse response = result.getResponse();
        assertThat(reachedTheChain(result)).isTrue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getCookies()).isEmpty();
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }
}
