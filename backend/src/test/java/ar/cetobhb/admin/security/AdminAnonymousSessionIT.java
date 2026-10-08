package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import adminprobe.AdminProbeController;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * An anonymous admin request never creates a server session (review fix batch 5, item 6), on MockMvc. The CSRF token
 * lives in a cookie, not in an {@code HttpSession}: with the default repository the first anonymous POST opened a
 * session and answered {@code Set-Cookie: JSESSIONID}. The status of each method is pinned: a read is
 * unauthenticated (401), a mutation is refused by CSRF (403) before authentication is even consulted.
 */
class AdminAnonymousSessionIT extends AbstractAdminProbeHttpIT {

    record Case(HttpMethod method, int status) {
        @Override
        public String toString() {
            return method + " -> " + status;
        }
    }

    static Stream<Case> cases() {
        return List.of(new Case(HttpMethod.GET, 401), new Case(HttpMethod.POST, 403), new Case(HttpMethod.PUT, 403),
                new Case(HttpMethod.PATCH, 403), new Case(HttpMethod.DELETE, 403)).stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void anAnonymousRequestOpensNoSessionAndSetsNoSessionCookie(Case c) throws Exception {
        MvcResult result = sendForResult(request(c.method(), "/api/admin/probe").content("{}")
                .contentType("application/json"));
        MockHttpServletResponse response = result.getResponse();

        assertThat(response.getStatus()).isEqualTo(c.status());
        assertThat(response.getContentAsString()).doesNotContain(AdminProbeController.SENTINEL);
        assertThat(result.getRequest().getSession(false)).as("HttpSession").isNull();
        assertThat(response.getHeaders("Set-Cookie")).noneMatch(cookie -> cookie.contains("JSESSIONID"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theOnlyCookieAnAnonymousRequestMayReceiveIsTheXsrfToken(Case c) throws Exception {
        MockHttpServletResponse response = send(request(c.method(), "/api/admin/probe").content("{}")
                .contentType("application/json"));

        assertThat(response.getHeaders("Set-Cookie")).allMatch(cookie -> cookie.startsWith("XSRF-TOKEN="));
    }
}
