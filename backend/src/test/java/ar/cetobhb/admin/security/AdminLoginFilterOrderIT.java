package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractHttpIT;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The position of the login limiter in the admin chain, read from the real chain (REQ-AUTH-10): after the header
 * writer (so its 429 carries the hardening headers), then the limiter, then the request shape guard, then CSRF, and
 * only then the filter that authenticates. The behaviour is pinned by {@code AdminLoginBurstServerIT}; this pins the
 * order itself, so moving the limiter behind CSRF or the guard fails with a clear reason.
 */
class AdminLoginFilterOrderIT extends AbstractHttpIT {

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private List<Class<? extends Filter>> loginChain() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/login");
        List<Filter> filters = springSecurityFilterChain.getFilterChains().stream()
                .filter(chain -> chain.matches(request)).findFirst().orElseThrow().getFilters();
        assertThat(filters).as("the admin chain matched the login path").isNotEmpty();
        return filters.stream().<Class<? extends Filter>>map(Filter::getClass).toList();
    }

    @Test
    void theLimiterRunsAfterTheHeaderWriterAndBeforeTheShapeGuardCsrfAndAuthentication() {
        List<Class<? extends Filter>> chain = loginChain();

        int headers = chain.indexOf(HeaderWriterFilter.class);
        int limiter = chain.indexOf(AdminLoginRateLimitFilter.class);
        int guard = chain.indexOf(LoginRequestGuardFilter.class);
        int csrf = chain.indexOf(CsrfFilter.class);
        int authentication = chain.indexOf(UsernamePasswordAuthenticationFilter.class);

        assertThat(limiter).as("the limiter is in the login chain").isNotNegative();
        assertThat(headers).as("header writer").isNotNegative().isLessThan(limiter);
        assertThat(limiter).as("limiter before the shape guard").isLessThan(guard);
        assertThat(guard).as("shape guard before CSRF").isLessThan(csrf);
        assertThat(csrf).as("CSRF before authentication").isLessThan(authentication);
    }

    @Test
    void theLimiterIsInTheChainExactlyOnce() {
        assertThat(loginChain().stream().filter(AdminLoginRateLimitFilter.class::equals).count()).isEqualTo(1);
    }
}
