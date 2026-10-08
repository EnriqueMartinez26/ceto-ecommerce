package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.support.MutableClock;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The absolute session lifetime, decided with an injected clock and never by waiting (review fix batch 7): a session
 * older than the limit (strictly: exactly the limit is still valid) ends however active it is. An authenticated session
 * with no login instant is treated as expired, so a session that skipped the login handler can never live forever.
 * The three requests that open or close a session (bootstrap, login, logout) continue as anonymous instead of
 * answering 401, so an expired cookie never locks the owner out.
 */
class AbsoluteSessionTimeoutFilterTest {

    private static final Duration LIMIT = Duration.ofMinutes(720);
    private static final Instant LOGIN = Instant.parse("2026-05-01T08:00:00Z");

    private final MutableClock clock = new MutableClock();
    private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(clock, LIMIT,
            new ProblemResponses(JsonMapper.builder().build()));

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void freshRequest() {
        clock.set(LOGIN);
        request = new MockHttpServletRequest("GET", "/api/admin/zzz-unmapped-probe");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private HttpSession loggedInSession() {
        HttpSession session = request.getSession(true);
        session.setAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE, LOGIN);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of()));
        return session;
    }

    private void run() throws Exception {
        filter.doFilter(request, response, chain);
    }

    @Test
    void aRequestWithoutASessionPassesAndOpensNone() throws Exception {
        run();

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void aSessionYoungerThanTheLimitPasses() throws Exception {
        HttpSession session = loggedInSession();
        clock.set(LOGIN.plus(LIMIT).minusSeconds(1));

        run();

        assertThat(chain.getRequest()).isNotNull();
        assertThat(((org.springframework.mock.web.MockHttpSession) session).isInvalid()).isFalse();
    }

    @Test
    void aSessionExactlyAsOldAsTheLimitIsStillValid() throws Exception {
        loggedInSession();
        clock.set(LOGIN.plus(LIMIT));

        run();

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aSessionOlderThanTheLimitByOneMillisecondEndsWithTheUnauthenticated401() throws Exception {
        HttpSession session = loggedInSession();
        clock.set(LOGIN.plus(LIMIT).plusMillis(1));

        run();

        assertThat(chain.getRequest()).as("the chain is not reached").isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(((org.springframework.mock.web.MockHttpSession) session).isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anAuthenticatedSessionWithNoLoginInstantIsStampedNowAndBoundedFromThere() throws Exception {
        HttpSession session = request.getSession(true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of()));

        run();

        assertThat(chain.getRequest()).isNotNull();
        assertThat(session.getAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE)).isEqualTo(LOGIN);

        chain = new MockFilterChain();
        clock.set(LOGIN.plus(LIMIT).plusSeconds(1));
        run();

        assertThat(response.getStatus()).as("not immortal: it expires like any other session").isEqualTo(401);
    }

    @Test
    void aMalformedLoginInstantIsReplacedByTheInstantOfTheFirstRequest() throws Exception {
        HttpSession session = loggedInSession();
        session.setAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE, "yesterday");

        run();

        assertThat(chain.getRequest()).isNotNull();
        assertThat(session.getAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE)).isEqualTo(LOGIN);
    }

    @Test
    void anAnonymousSessionWithoutAnInstantPassesBecauseThereIsNothingToExpire() throws Exception {
        request.getSession(true);

        run();

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aClockThatMovedBackwardsDoesNotExpireTheSession() throws Exception {
        loggedInSession();
        clock.set(LOGIN.minusSeconds(3_600));

        run();

        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET,/api/admin/session", "POST,/api/admin/login", "POST,/api/admin/logout"})
    void theRequestsThatOpenOrCloseASessionContinueAsAnonymousAfterTheExpiredSessionEnds(String method, String path)
            throws Exception {
        request = new MockHttpServletRequest(method, path);
        HttpSession session = loggedInSession();
        clock.set(LOGIN.plus(LIMIT).plusSeconds(1));

        run();

        assertThat(chain.getRequest()).as("the chain is reached").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((org.springframework.mock.web.MockHttpSession) session).isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("anonymous from here on").isNull();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET,/api/admin/session/", "POST,/api/admin/session", "GET,/api/admin/login", "GET,/api/admin/logout",
        "GET,/api/admin/%73ession", "GET,/api/admin/Session", "DELETE,/api/admin/orders"})
    void anyOtherRequestWithAnExpiredSessionIsA401(String method, String path) throws Exception {
        request = new MockHttpServletRequest(method, path);
        loggedInSession();
        clock.set(LOGIN.plus(LIMIT).plusSeconds(1));

        run();

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
