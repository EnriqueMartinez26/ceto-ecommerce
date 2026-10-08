package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractHttpIT;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The last Security chain has no matcher and denies everything (review fix batch 5, item 4). The servlet filter
 * mapping already keeps public routes out of Security, so this chain only ever sees {@code /api/admin/*}; it exists so
 * that a future admin path which the admin chain's matcher does not cover (a narrowed or mistyped matcher) is refused
 * instead of passing through to a handler with no authentication at all.
 *
 * <p>The tests drive {@link FilterChainProxy} directly with requests the admin chain does not match, which is exactly
 * the situation the fallback is for.
 */
class AdminFallbackDenyChainIT extends AbstractHttpIT {

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private FilterChainProxy filterChainProxy;

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("198.18.200.1");
        return request;
    }

    @Test
    void theLastChainMatchesEveryRequestAndTheAdminChainComesFirst() {
        List<SecurityFilterChain> chains = filterChainProxy.getFilterChains();

        assertThat(chains).hasSize(2);
        assertThat(chains.get(0).matches(request("GET", "/api/admin/orders"))).isTrue();
        assertThat(chains.get(0).matches(request("GET", "/api/store/products"))).isFalse();
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")) {
            assertThat(chains.get(1).matches(request(method, "/anything/at/all")))
                    .as("fallback matches %s", method).isTrue();
        }
    }

    @Test
    void aRequestTheAdminChainDoesNotMatchIsDeniedAndNeverReachesTheServlet() throws Exception {
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reachedTheServlet = new AtomicBoolean();

            filterChainProxy.doFilter(request(method, "/uncovered/admin/path"), response,
                    (req, res) -> reachedTheServlet.set(true));

            assertThat(reachedTheServlet).as("%s reached the servlet", method).isFalse();
            assertThat(response.getStatus()).as("%s status", method).isEqualTo(401);
            assertThat(response.getHeaders("Set-Cookie")).as("%s cookies", method).isEmpty();
        }
    }
}
