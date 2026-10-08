package ar.cetobhb.admin.security;

import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.common.web.SecurityProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pins the shape of the login request before anything reads it (review fix batch 7). The login contract is a body
 * only, {@code application/x-www-form-urlencoded}, with exactly one {@code username} and one {@code password}. The
 * servlet container merges query parameters with body parameters and two values of a field are a parser differential
 * waiting to happen, so anything else is refused here:
 *
 * <ul>
 * <li>a query string (even an empty one): 400 {@code MALFORMED_REQUEST};</li>
 * <li>a {@code Content-Type} other than urlencoded (any parameters such as {@code charset} are fine, none or JSON or
 * multipart are not): 415;</li>
 * <li>a repeated or missing {@code username} or {@code password}, or a body the container cannot decode: 400.</li>
 * </ul>
 *
 * <p>It runs before the CSRF filter and before the login filter, so a refused request costs no BCrypt work and opens no
 * session. It only looks at the login processing request (the same matcher the login filter uses); every other request
 * passes untouched. It is created by the chain configuration and is NOT a bean, so the servlet container never
 * registers it a second time. Responses are the fixed problem bodies: nothing from the request is echoed or logged.
 */
final class LoginRequestGuardFilter extends OncePerRequestFilter {

    static final String USERNAME_PARAMETER = "username";
    static final String PASSWORD_PARAMETER = "password";

    private final RequestMatcher loginRequest;
    private final ProblemResponses problems;

    LoginRequestGuardFilter(String loginPath, ProblemResponses problems) {
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
        if (request.getQueryString() != null) {
            problems.write(request, response, SecurityProblem.REJECTED_REQUEST);
            return;
        }
        if (!isUrlencoded(request.getContentType())) {
            problems.write(request, response, SecurityProblem.UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        if (!hasExactlyOneValue(request, USERNAME_PARAMETER) || !hasExactlyOneValue(request, PASSWORD_PARAMETER)) {
            problems.write(request, response, SecurityProblem.REJECTED_REQUEST);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isUrlencoded(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException unparsable) {
            return false;
        }
    }

    /** A body the container fails to decode (bad percent-encoding, over the form limit) counts as not one value. */
    private static boolean hasExactlyOneValue(HttpServletRequest request, String name) {
        try {
            String[] values = request.getParameterValues(name);
            return values != null && values.length == 1;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
