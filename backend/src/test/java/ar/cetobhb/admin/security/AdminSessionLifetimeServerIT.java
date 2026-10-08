package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.apache.catalina.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The absolute session lifetime on the real server (review fix batch 7): a session ends
 * {@code ceto.admin.session-absolute-timeout-minutes} (720 by default) after its login, however active it is. The time
 * is the injected clock, moved by the test: nothing here sleeps. Also: an expired or superseded session cookie must not
 * lock the owner out, so the bootstrap, the login and the logout continue as anonymous instead of answering 401.
 */
class AdminSessionLifetimeServerIT extends AbstractAdminServerIT {

    private static final String ANONYMOUS = "{\"authenticated\":false}";

    @Autowired
    private MutableClock clock;

    @Autowired
    private AdminProperties admin;

    private Duration limit() {
        return Duration.ofMinutes(admin.sessionAbsoluteTimeoutMinutes());
    }

    @Test
    void theShippedLimitIsTwelveHours() {
        assertThat(admin.sessionAbsoluteTimeoutMinutes()).isEqualTo(720);
    }

    @Test
    void theLoginStoresTheInstantOfTheInjectedClockInTheSession() throws Exception {
        Instant at = MutableClock.START.plusSeconds(1_234);
        clock.set(at);

        AdminClient client = loggedInClient();

        Session session = findServerSession(client.cookie(AdminClient.SESSION_COOKIE));
        assertThat(session.getSession().getAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE)).isEqualTo(at);
    }

    @Test
    void aSessionJustInsideTheLimitIsStillValid() throws Exception {
        AdminClient client = loggedInClient();

        clock.set(MutableClock.START.plus(limit()).minusSeconds(1));

        assertThat(client.isAuthenticated()).isTrue();
    }

    @Test
    void aSessionPastTheLimitIsTheUnauthenticated401AndIsGoneFromTheServer() throws Exception {
        AdminClient client = loggedInClient();
        String id = client.cookie(AdminClient.SESSION_COOKIE);
        assertThat(client.isAuthenticated()).isTrue();

        clock.set(MutableClock.START.plus(limit()).plusSeconds(1));
        AdminClient.Reply reply = client.get(AdminClient.PROBE_PATH);

        assertThat(reply.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-unauthenticated.json", AdminClient.PROBE_PATH);
        assertThat(findServerSession(id)).as("the session was invalidated").isNull();
        assertThat(client.get(AdminClient.PROBE_PATH).status()).as("and stays dead").isEqualTo(401);
    }

    @Test
    void activityDoesNotExtendTheLifetime() throws Exception {
        AdminClient client = loggedInClient();
        long half = limit().toMinutes() * 6 / 10;

        clock.set(MutableClock.START.plus(Duration.ofMinutes(half)));
        assertThat(client.isAuthenticated()).as("first use").isTrue();
        clock.set(MutableClock.START.plus(Duration.ofMinutes(2 * half)));

        assertThat(client.get(AdminClient.PROBE_PATH).status()).as("second use, past the limit from the login")
                .isEqualTo(401);
    }

    @Test
    void aMutationWithAnExpiredSessionIs401TooNotACsrfOrAnAuthorizedCall() throws Exception {
        AdminClient client = loggedInClient();
        client.bootstrap();

        clock.set(MutableClock.START.plus(limit()).plusSeconds(1));

        assertThat(client.postWithToken(AdminClient.PROBE_PATH).status()).isEqualTo(401);
    }

    @Test
    void anAuthenticatedSessionWithoutALoginInstantIsStampedAndThenExpiresNotImmortal() throws Exception {
        AdminClient client = loggedInClient();
        Session session = findServerSession(client.cookie(AdminClient.SESSION_COOKIE));
        session.getSession().removeAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE);
        clock.set(MutableClock.START.plusSeconds(60));

        assertThat(client.isAuthenticated()).as("stamped at its first request").isTrue();
        assertThat(session.getSession().getAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE))
                .isEqualTo(clock.instant());
        clock.set(clock.instant().plus(limit()).plusSeconds(1));

        assertThat(client.get(AdminClient.PROBE_PATH).status()).isEqualTo(401);
    }

    @Test
    void anAbsoluteExpiredSessionOnTheBootstrapIs200NotAuthenticatedWithAFreshXsrfCookie() throws Exception {
        AdminClient owner = loggedInClient();
        String stale = owner.cookie(AdminClient.SESSION_COOKIE);
        clock.set(MutableClock.START.plus(limit()).plusSeconds(1));
        AdminClient browser = newClient();
        browser.setCookie(AdminClient.SESSION_COOKIE, stale);

        AdminClient.Reply reply = browser.bootstrap();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo(ANONYMOUS);
        assertThat(reply.setCookie(AdminClient.CSRF_COOKIE)).as("the XSRF cookie is delivered").isNotNull();
        assertThat(findServerSession(stale)).isNull();
    }

    @Test
    void anAbsoluteExpiredSessionDoesNotBlockTheNextLogin() throws Exception {
        AdminClient owner = loggedInClient();
        clock.set(MutableClock.START.plus(limit()).plusSeconds(1));

        AdminClient.Reply login = owner.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(login.status()).isEqualTo(204);
        assertThat(owner.isAuthenticated()).isTrue();
        Session fresh = findServerSession(owner.cookie(AdminClient.SESSION_COOKIE));
        assertThat(fresh.getSession().getAttribute(AdminLoginSuccessHandler.LOGIN_AT_ATTRIBUTE))
                .isEqualTo(clock.instant());
    }

    @Test
    void anAbsoluteExpiredSessionCanStillLogOut() throws Exception {
        AdminClient owner = loggedInClient();
        owner.bootstrap();
        clock.set(MutableClock.START.plus(limit()).plusSeconds(1));

        assertThat(owner.logout().status()).isEqualTo(204);
    }

    @Test
    void aSupersededSessionOnTheBootstrapIs200NotAuthenticatedWithAFreshXsrfCookie() throws Exception {
        AdminClient first = loggedInClient();
        String stale = first.cookie(AdminClient.SESSION_COOKIE);
        loggedInClient();
        AdminClient browser = newClient();
        browser.setCookie(AdminClient.SESSION_COOKIE, stale);

        AdminClient.Reply reply = browser.bootstrap();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo(ANONYMOUS);
        assertThat(reply.setCookie(AdminClient.CSRF_COOKIE)).as("the XSRF cookie is delivered").isNotNull()
                .contains("Secure");
        assertThat(browser.get(AdminClient.PROBE_PATH).status()).as("and the cookie stays dead").isEqualTo(401);
    }

    @Test
    void theSupersededBrowserItselfGetsTheAnonymousBootstrapAndCanLogInAgain() throws Exception {
        AdminClient first = loggedInClient();
        AdminClient second = loggedInClient();

        AdminClient.Reply bootstrap = first.bootstrap();
        assertThat(bootstrap.status()).isEqualTo(200);
        assertThat(bootstrap.body()).isEqualTo(ANONYMOUS);
        AdminClient.Reply login = first.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(login.status()).isEqualTo(204);
        assertThat(first.isAuthenticated()).isTrue();
        assertThat(second.get(AdminClient.PROBE_PATH).status()).as("the newer login superseded the other").isEqualTo(401);
    }

    @Test
    void aSupersededSessionStillGets401OnEveryOtherRoute() throws Exception {
        AdminClient first = loggedInClient();
        loggedInClient();

        assertThat(first.get(AdminClient.PROBE_PATH).status()).isEqualTo(401);
        assertThat(first.get("/api/admin/session/").status()).as("trailing slash is not the bootstrap").isEqualTo(401);
    }
}
