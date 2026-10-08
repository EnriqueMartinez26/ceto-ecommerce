package ar.cetobhb.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * A correct login answers 204 with no body and prepares the new session: the idle timeout
 * ({@code ceto.admin.session-idle-timeout-minutes}, 5 to 1440, REQ-AUTH-09) and the login instant, which the
 * {@link AbsoluteSessionTimeoutFilter} compares with {@code ceto.admin.session-absolute-timeout-minutes}. Both are set
 * here, per session: the idle timeout and not with {@code server.servlet.session.timeout} because Boot hands the
 * container a whole number of minutes, and this way one place owns the value and the test can read it back from the
 * live session. The instant comes from the injected {@link Clock}, so a test controls it.
 */
final class AdminLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** Session attribute holding the {@link java.time.Instant} of the login that created the session. */
    static final String LOGIN_AT_ATTRIBUTE = "ceto.admin.loginAt";

    private final int idleTimeoutSeconds;
    private final Clock clock;

    AdminLoginSuccessHandler(int idleTimeoutMinutes, Clock clock) {
        this.idleTimeoutSeconds = Math.multiplyExact(idleTimeoutMinutes, 60);
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) {
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(idleTimeoutSeconds);
        session.setAttribute(LOGIN_AT_ATTRIBUTE, clock.instant());
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }
}
