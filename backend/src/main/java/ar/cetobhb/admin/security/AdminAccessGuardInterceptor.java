package ar.cetobhb.admin.security;

import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.common.web.SecurityProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Second line behind the Security chain (design AD-A3, REQ-AUTH-11): no {@code /api/admin/**} handler runs for a
 * request that has no principal, whatever the servlet filter mapping and the chain matcher decided. It is an MVC
 * interceptor, so it uses the same path matching as the handlers themselves: if Tomcat's mapping and Spring MVC's ever
 * disagree about a spelling of an admin path, the handler is still guarded.
 *
 * <p>Only {@code GET /api/admin/session} ({@link SessionBootstrapRequest}, the same matcher the Security chain
 * uses), the bootstrap that tells an anonymous client whether it is logged in, is exempt. The guard checks that
 * someone passed Security, not who it was; roles are not used.
 */
public final class AdminAccessGuardInterceptor implements HandlerInterceptor {

    /** The handlers this interceptor protects. */
    public static final String PATH_PATTERN = "/api/admin/**";

    private final ProblemResponses problems;

    public AdminAccessGuardInterceptor(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (request.getUserPrincipal() != null || SessionBootstrapRequest.isBootstrap(request)) {
            return true;
        }
        problems.write(request, response, SecurityProblem.UNAUTHENTICATED);
        return false;
    }
}
