package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistryImpl;

/**
 * A session that a newer login superseded is dropped quietly, and only for the requests that open or close a session
 * (bootstrap, login, logout): Security's concurrency filter would answer them with the 401 and expire the XSRF cookie.
 * Everything else is left to that filter and keeps the 401.
 */
class SupersededSessionFilterTest {

    private final SessionRegistryImpl registry = new SessionRegistryImpl();
    private final SupersededSessionFilter filter = new SupersededSessionFilter(registry);
    private MockHttpSession session;

    @BeforeEach
    void aRegisteredSession() {
        session = new MockHttpSession();
        registry.registerNewSession(session.getId(), "admin");
    }

    private MockFilterChain run(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setSession(session);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET,/api/admin/session", "POST,/api/admin/login", "POST,/api/admin/logout"})
    void aSupersededSessionIsDroppedAndTheLifecycleRequestContinues(String method, String path) throws Exception {
        registry.getSessionInformation(session.getId()).expireNow();

        MockFilterChain chain = run(method, path);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(session.isInvalid()).isTrue();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET,/api/admin/zzz-unmapped-probe", "GET,/api/admin/session/", "POST,/api/admin/session",
        "GET,/api/admin/login", "DELETE,/api/admin/orders"})
    void anyOtherRequestIsLeftAloneForSecuritysOwn401(String method, String path) throws Exception {
        registry.getSessionInformation(session.getId()).expireNow();

        MockFilterChain chain = run(method, path);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(session.isInvalid()).as("not touched here").isFalse();
    }

    @Test
    void aSessionThatIsNotSupersededIsNeverTouched() throws Exception {
        MockFilterChain chain = run("GET", "/api/admin/session");

        assertThat(chain.getRequest()).isNotNull();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aSessionTheRegistryDoesNotKnowIsNeverTouched() throws Exception {
        registry.removeSessionInformation(session.getId());

        MockFilterChain chain = run("GET", "/api/admin/session");

        assertThat(chain.getRequest()).isNotNull();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aRequestWithoutASessionPassesAndOpensNone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/session");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getSession(false)).isNull();
    }
}
