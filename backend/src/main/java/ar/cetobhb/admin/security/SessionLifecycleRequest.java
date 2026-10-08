package ar.cetobhb.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The requests that open or close an administrator session: the bootstrap ({@code GET /api/admin/session}, the exact
 * match of {@link SessionBootstrapRequest}), the login and the logout (the same matchers the Security filters use). A
 * browser that still holds the cookie of a session that ended (superseded by a newer login, or past its absolute
 * lifetime) must be able to make exactly these requests, so for them the dead session is dropped and the request
 * continues as anonymous. Every other request with a dead session is the 401 problem.
 */
final class SessionLifecycleRequest implements RequestMatcher {

    private static final RequestMatcher LOGIN = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, AdminSecurityChainConfig.LOGIN_PATH);
    private static final RequestMatcher LOGOUT = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, AdminSecurityChainConfig.LOGOUT_PATH);

    @Override
    public boolean matches(HttpServletRequest request) {
        return SessionBootstrapRequest.isBootstrap(request) || LOGIN.matches(request) || LOGOUT.matches(request);
    }
}
