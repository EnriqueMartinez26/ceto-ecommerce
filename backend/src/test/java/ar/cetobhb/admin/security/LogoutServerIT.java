package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.ContractAssertions;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Logout (REQ-AUTH-08): CSRF-protected, 204, the server session ends and the old cookie is worth nothing. */
class LogoutServerIT extends AbstractAdminServerIT {

    private static final String LOGOUT = "/api/admin/logout";

    @Test
    void logoutWithTheXsrfHeaderIs204ExpiresTheCookieAndTheOldCookieIsA401() throws Exception {
        AdminClient client = loggedInClient();
        client.bootstrap();
        String oldSession = client.cookie(AdminClient.SESSION_COOKIE);
        assertThat(client.isAuthenticated()).isTrue();

        AdminClient.Reply logout = client.logout();

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        String cleared = logout.setCookie("JSESSIONID");
        assertThat(cleared).as("the session cookie is expired").isNotNull().startsWith("JSESSIONID=;")
                .contains("Expires=Thu, 01 Jan 1970");
        assertThat(client.cookie(AdminClient.SESSION_COOKIE)).isNull();
        assertThat(findServerSession(oldSession)).as("the server session ended").isNull();

        AdminClient replay = newClient();
        replay.setCookie(AdminClient.SESSION_COOKIE, oldSession);
        AdminClient.Reply probe = replay.get(AdminClient.PROBE_PATH);
        assertThat(probe.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(probe.body(), probe.header("x-request-id"),
                "admin-error-unauthenticated.json", AdminClient.PROBE_PATH);
    }

    @Test
    void logoutWithoutACsrfTokenIs403AndTheSessionSurvives() throws Exception {
        AdminClient client = loggedInClient();

        AdminClient.Reply logout = client.send("POST", LOGOUT, null, null, Map.of());

        assertThat(logout.status()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(logout.body(), logout.header("x-request-id"),
                "admin-error-forbidden.json", LOGOUT);
        assertThat(client.isAuthenticated()).as("nothing was logged out").isTrue();
    }

    @Test
    void logoutWithAGarbageTokenIs403() throws Exception {
        AdminClient client = loggedInClient();
        client.bootstrap();

        AdminClient.Reply logout = client.send("POST", LOGOUT, null, null, Map.of("X-XSRF-TOKEN", "garbage"));

        assertThat(logout.status()).isEqualTo(403);
        assertThat(client.isAuthenticated()).isTrue();
    }

    @Test
    void logoutWithoutASessionIsA204NotA5xx() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply logout = client.logout();

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.status()).isLessThan(500);
    }

    @Test
    void afterLogoutTheXsrfTokenIsRotatedAndANewLoginWorksFromAFreshBootstrap() throws Exception {
        AdminClient client = loggedInClient();
        client.bootstrap();
        String before = client.xsrf();

        AdminClient.Reply logout = client.logout();

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookies()).as("the logout expires the token cookie")
                .anyMatch(line -> line.startsWith("XSRF-TOKEN=;"));
        assertThat(client.xsrf()).as("the token cookie does not survive the logout").isNotEqualTo(before);
        client.bootstrap();
        assertThat(client.xsrf()).isNotBlank().isNotEqualTo(before);
        assertThat(client.isAuthenticated()).isFalse();
    }

    @Test
    void aGetOnTheLogoutPathDoesNotLogOut() throws Exception {
        AdminClient client = loggedInClient();

        AdminClient.Reply reply = client.get(LOGOUT);

        assertThat(reply.status()).isNotEqualTo(204);
        assertThat(reply.status()).isLessThan(500);
        assertThat(client.isAuthenticated()).isTrue();
    }
}
