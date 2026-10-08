package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The anonymous allowance is exactly {@code GET /api/admin/session}, matched on the path AS WRITTEN: the Security chain
 * and {@link AdminAccessGuardInterceptor} both ask this class, so a spelling that decodes to the same segments (a
 * pattern matcher would accept {@code %73ession}) or a different method can never widen it.
 */
class SessionBootstrapRequestTest {

    private static boolean matches(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        return new SessionBootstrapRequest().matches(request);
    }

    @Test
    void theExactGetMatches() {
        assertThat(matches("GET", "/api/admin/session")).isTrue();
        assertThat(SessionBootstrapRequest.isBootstrap(new MockHttpServletRequest("GET", "/api/admin/session"))).isTrue();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"HEAD,/api/admin/session", "POST,/api/admin/session", "PUT,/api/admin/session",
            "PATCH,/api/admin/session", "DELETE,/api/admin/session", "OPTIONS,/api/admin/session",
            "TRACE,/api/admin/session", "get,/api/admin/session"})
    void anyOtherMethodDoesNotMatch(String method, String uri) {
        // The comparison is the exact HTTP method name: even a lower-case "get" is not GET.
        assertThat(matches(method, uri)).isFalse();
    }

    @ParameterizedTest(name = "GET {0}")
    @CsvSource({"/api/admin/%73ession", "/api/admin/%53ession", "/api/admin/Session", "/api/admin/SESSION",
            "/api/admin/session/", "/api/admin/session/x", "/api/admin/session/..", "/api/admin//session",
            "/api/admin/session;x=1", "/api/admin/sessions", "/api/admin/session%2F", "/API/admin/session",
            "/api/admin", "/api/admin/", "/api/admin/orders", "/session", "/api/store/session"})
    void anyOtherSpellingOrPathDoesNotMatch(String uri) {
        assertThat(matches("GET", uri)).isFalse();
    }

    @Test
    void theContextPathIsNotPartOfThePath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ceto/api/admin/session");
        request.setContextPath("/ceto");

        assertThat(new SessionBootstrapRequest().matches(request)).isTrue();
    }
}
