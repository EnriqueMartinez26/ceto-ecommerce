package ar.cetobhb.admin.security;

import ar.cetobhb.common.domain.ClientIpKey;
import ar.cetobhb.common.domain.TokenBucketLimiter;
import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.common.web.SecurityProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The brute-force limiter of the login (REQ-AUTH-10, design AD-A6): one token of the caller's bucket per login POST,
 * before anything else looks at the request. It is the FIRST filter of the login path: it runs ahead of
 * {@link LoginRequestGuardFilter} and of the CSRF filter, so every attempt costs a token whatever it carries (a
 * missing or garbage CSRF token, a query string, JSON, a repeated field) and a limited attempt never reaches the
 * authentication manager, so BCrypt never runs for it. Letting the shape guard go first would hand an attacker free
 * malformed attempts; the CSRF token is no barrier either, because the anonymous session endpoint hands one out.
 *
 * <p>The key comes ONLY from {@code getRemoteAddr()}, which behind the trusted proxy is the real client (the same rule
 * as the checkout limiter); this class never reads {@code X-Forwarded-For}. There is no per-account lockout and no
 * global bucket (D-20261008-07), but the owner can still be denied access if she shares an address or bucket key
 * with an attacker, or when the shared overflow bucket is drained (D-20261008-43). The address is never logged, and
 * neither is anything the client sent.
 *
 * <p>It only looks at the login processing request (the same method and path matcher as the shape guard and the
 * login filter); every other request passes untouched and spends nothing. It is created by the chain configuration and
 * is NOT a bean, so the servlet container never registers it a second time.
 */
final class AdminLoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AdminLoginRateLimitFilter.class);

    private final TokenBucketLimiter limiter;
    private final RequestMatcher loginRequest;
    private final ProblemResponses problems;

    AdminLoginRateLimitFilter(TokenBucketLimiter limiter, String loginPath, ProblemResponses problems) {
        this.limiter = limiter;
        this.loginRequest = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, loginPath);
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!loginRequest.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(ClientIpKey.of(request.getRemoteAddr()));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        // A client can trigger this at will: DEBUG only, and nothing about the caller (address, fields) is logged.
        LOG.debug("Admin login attempt rejected by the rate limit");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        problems.write(request, response, SecurityProblem.LOGIN_RATE_LIMITED);
    }
}
