package ar.cetobhb.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A newer login marks the older administrator session as expired in the registry. Security's concurrency filter then
 * answers every request of that session with the 401 problem, and it first runs the logout handlers, which also expire
 * the CSRF cookie. For the requests that open or close a session ({@link SessionLifecycleRequest}: the bootstrap, the
 * login and the logout) that would be wrong: the browser must still be able to ask {@code GET /api/admin/session}
 * and receive {@code {"authenticated":false}} with a usable XSRF cookie (review fix batch 7).
 *
 * <p>So for those requests only, a superseded session is invalidated here, quietly, and the request continues as
 * anonymous; the concurrency filter then finds no session. Every other request is left alone and keeps the 401.
 */
final class SupersededSessionFilter extends OncePerRequestFilter {

    private final SessionRegistry registry;
    private final RequestMatcher opensOrClosesASession = new SessionLifecycleRequest();

    SupersededSessionFilter(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && opensOrClosesASession.matches(request) && isSuperseded(session)) {
            session.invalidate();
            SecurityContextHolder.getContext().setAuthentication(null);
        }
        chain.doFilter(request, response);
    }

    private boolean isSuperseded(HttpSession session) {
        SessionInformation information = registry.getSessionInformation(session.getId());
        return information != null && information.isExpired();
    }
}
