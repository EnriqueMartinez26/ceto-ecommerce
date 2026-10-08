package ar.cetobhb.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The single request an anonymous client may make to the administration area: {@code GET /api/admin/session}, the
 * bootstrap that says whether the caller is logged in and hands out the CSRF cookie. The Security chain permits it
 * and {@link AdminAccessGuardInterceptor} exempts it through THIS class, so the two cannot drift apart.
 *
 * <p>The match is exact on the method (GET only: not HEAD, not POST, not OPTIONS) and on the path AS WRITTEN: the
 * undecoded path within the application must equal {@value #PATH}. A pattern matcher would also accept the spellings
 * that decode to the same segments ({@code /api/admin/%73ession}); the allowance deliberately does not, so a
 * spelling gap can never widen it. Case, a trailing slash and extra segments are different paths.
 */
final class SessionBootstrapRequest implements RequestMatcher {

    static final String PATH = "/api/admin/session";

    @Override
    public boolean matches(HttpServletRequest request) {
        return isBootstrap(request);
    }

    static boolean isBootstrap(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod()) && PATH.equals(pathWithinApplication(request));
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
            return ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication().value();
        }
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context.isEmpty() || !uri.startsWith(context) ? uri : uri.substring(context.length());
    }
}
