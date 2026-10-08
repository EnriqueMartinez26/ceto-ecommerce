package ar.cetobhb.admin.security;

import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.common.web.SecurityProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Absolute lifetime of an administrator session (review fix batch 7, decision D-20261008-39): the idle timeout alone
 * lets a session that is used every few hours live for ever, so a session also ends once
 * {@code ceto.admin.session-absolute-timeout-minutes} have passed since its login, however active it is. The login
 * instant is the {@link AdminLoginSuccessHandler#LOGIN_AT_ATTRIBUTE} stored by the success handler from the injected
 * clock; tests move the clock, nothing waits.
 *
 * <p>It runs after the security context is loaded. When the session is past the limit it is invalidated and the
 * request is the 401 {@code UNAUTHENTICATED} problem, except for the requests that open or close a session
 * ({@link SessionLifecycleRequest}), which continue as anonymous so a stale cookie never locks the owner out. An
 * authenticated session with no usable login instant (it did not come through the login handler, as with the
 * {@code user()} post-processor of MockMvc tests) gets the instant of its first request, so it is bounded from there
 * and never immortal. A session with no authentication has nothing to expire. The limit is strict: a session exactly as
 * old as the limit is still valid.
 */
final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final Duration timeout;
    private final ProblemResponses problems;
    private final RequestMatcher opensOrClosesASession = new SessionLifecycleRequest();

    AbsoluteSessionTimeoutFilter(Clock clock, Duration timeout, ProblemResponses problems) {
        this.clock = clock;
        this.timeout = timeout;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || !isExpired(session)) {
            chain.doFilter(request, response);
            return;
        }
        session.invalidate();
        SecurityContextHolder.getContext().setAuthentication(null);
        if (opensOrClosesASession.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        problems.write(request, response, SecurityProblem.UNAUTHENTICATED);
    }

    private boolean isExpired(HttpSession session) {
        Object loginAt = session.getAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE);
        if (loginAt instanceof Instant instant) {
            return Duration.between(instant, clock.instant()).compareTo(timeout) > 0;
        }
        if (isAuthenticated()) {
            // No usable instant on an authenticated session (it did not come through the login handler): start the
            // clock now, so the session is bounded from here instead of living for ever.
            session.setAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE, clock.instant());
        }
        return false;
    }

    private static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
