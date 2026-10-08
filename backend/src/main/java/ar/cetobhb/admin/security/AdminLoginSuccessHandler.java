package ar.cetobhb.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * A correct login answers 204 with no body and sets the idle timeout of the new session
 * ({@code ceto.admin.session-idle-timeout-minutes}, 5 to 1440, REQ-AUTH-09). It is set here, per session, and not with
 * {@code server.servlet.session.timeout}: Boot hands the container a whole number of minutes, and this way one place
 * owns the value and the test can read it back from the live session.
 */
final class AdminLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final int idleTimeoutSeconds;

    AdminLoginSuccessHandler(int idleTimeoutMinutes) {
        this.idleTimeoutSeconds = Math.multiplyExact(idleTimeoutMinutes, 60);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) {
        request.getSession(true).setMaxInactiveInterval(idleTimeoutSeconds);
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }
}
