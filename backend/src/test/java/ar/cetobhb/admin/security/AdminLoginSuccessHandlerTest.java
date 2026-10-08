package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/**
 * A successful login answers 204, sets the idle timeout of THAT session from
 * {@code ceto.admin.session-idle-timeout-minutes} (REQ-AUTH-09, design AD-A8: the success handler is the single source,
 * not Tomcat's global timeout) and stores the login instant, read from the injected clock, for the absolute limit.
 */
class AdminLoginSuccessHandlerTest {

    private final MutableClock clock = new MutableClock();

    @ParameterizedTest(name = "{0} minutes")
    @ValueSource(ints = {5, 60, 480, 1440})
    void theSessionGetsTheConfiguredIdleTimeoutInSeconds(int minutes) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/login");
        request.getSession(true).setMaxInactiveInterval(1800);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AdminLoginSuccessHandler(minutes, clock).onAuthenticationSuccess(request, response,
                UsernamePasswordAuthenticationToken.authenticated("admin", null, java.util.List.of()));

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(request.getSession(false).getMaxInactiveInterval()).isEqualTo(minutes * 60);
    }

    @ParameterizedTest(name = "{0} minutes")
    @ValueSource(ints = {1440})
    void theLargestTimeoutDoesNotOverflow(int minutes) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/login");
        request.getSession(true);

        new AdminLoginSuccessHandler(minutes, clock).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                UsernamePasswordAuthenticationToken.authenticated("admin", null, java.util.List.of()));

        assertThat(request.getSession(false).getMaxInactiveInterval()).isEqualTo(86_400);
    }

    @Test
    void theSessionRemembersTheLoginInstantOfTheInjectedClock() throws Exception {
        clock.set(Instant.parse("2026-05-01T08:30:00Z"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/login");

        new AdminLoginSuccessHandler(480, clock).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                UsernamePasswordAuthenticationToken.authenticated("admin", null, java.util.List.of()));

        assertThat(request.getSession(false).getAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE))
                .isEqualTo(Instant.parse("2026-05-01T08:30:00Z"));
    }

    @Test
    void aSecondLoginOnTheSameSessionObjectRestartsTheAbsoluteClock() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/login");
        AdminLoginSuccessHandler handler = new AdminLoginSuccessHandler(480, clock);
        handler.onAuthenticationSuccess(request, new MockHttpServletResponse(),
                UsernamePasswordAuthenticationToken.authenticated("admin", null, java.util.List.of()));
        clock.set(clock.instant().plusSeconds(3_600));

        handler.onAuthenticationSuccess(request, new MockHttpServletResponse(),
                UsernamePasswordAuthenticationToken.authenticated("admin", null, java.util.List.of()));

        assertThat(request.getSession(false).getAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE))
                .isEqualTo(clock.instant());
    }
}
