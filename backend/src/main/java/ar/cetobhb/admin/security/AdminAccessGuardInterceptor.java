package ar.cetobhb.admin.security;

import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.common.web.SecurityProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Second line behind the Security chain (design AD-A3, REQ-AUTH-11): no {@code /api/admin/**} handler runs for a
 * request that has no principal, whatever the servlet filter mapping and the chain matcher decided. It is an MVC
 * interceptor, so it uses the same path matching as the handlers themselves: if Tomcat's mapping and Spring MVC's ever
 * disagree about a spelling of an admin path, the handler is still guarded.
 *
 * <p>Only {@code GET /api/admin/session}, the bootstrap that tells an anonymous client whether it is logged in, is
 * exempt. The guard checks that someone passed Security, not who it was; roles are not used.
 */
public final class AdminAccessGuardInterceptor implements HandlerInterceptor {

    /** The handlers this interceptor protects. */
    public static final String PATH_PATTERN = "/api/admin/**";

    /** The one admin handler an anonymous client may call, and only with GET. */
    static final String SESSION_PATH = "/api/admin/session";

    private final ProblemResponses problems;

    public AdminAccessGuardInterceptor(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (request.getUserPrincipal() != null || isSessionBootstrap(request)) {
            return true;
        }
        problems.write(request, response, SecurityProblem.UNAUTHENTICATED);
        return false;
    }

    private static boolean isSessionBootstrap(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod()) && SESSION_PATH.equals(pathWithinApplication(request));
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
