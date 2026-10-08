package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/**
 * A successful login answers 204 and sets the idle timeout of THAT session from {@code ceto.admin.session-idle-timeout-minutes}
 * (REQ-AUTH-09, design AD-A8): the success handler is the single source, not Tomcat's global timeout.
 */
class AdminLoginSuccessHandlerTest {

    @ParameterizedTest(name = "{0} minutes")
    @ValueSource(ints = {5, 60, 480, 1440})
    void theSessionGetsTheConfiguredIdleTimeoutInSeconds(int minutes) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/login");
        request.getSession(true).setMaxInactiveInterval(1800);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AdminLoginSuccessHandler(minutes).onAuthenticationSuccess(request, response,
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

        new AdminLoginSuccessHandler(minutes).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                UsernamePasswordAuthenticationToken.authenticated("admin", null, java.util.List.of()));

        assertThat(request.getSession(false).getMaxInactiveInterval()).isEqualTo(86_400);
    }
}
