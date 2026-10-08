package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.ContractAssertions;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The whole administrator session on the real server, in one story (X5 closing): bootstrap, a login that rotates the
 * session id and sets the hardened cookie, an authenticated call that needs both the cookie and the XSRF header, the
 * logout, and the dead cookie afterwards. Each step is also pinned on its own elsewhere; this test proves they work
 * together on one browser.
 */
class AdminSessionFlowServerIT extends AbstractAdminServerIT {

    @Test
    void bootstrapLoginAuthenticatedCallLogoutAndTheReplayOfTheOldCookie() throws Exception {
        AdminClient browser = newClient();

        // 1. bootstrap: anonymous, hands out the XSRF cookie, opens no session.
        AdminClient.Reply bootstrap = browser.bootstrap();
        assertThat(bootstrap.status()).isEqualTo(200);
        assertThat(bootstrap.body()).isEqualTo("{\"authenticated\":false}");
        String firstToken = browser.xsrf();
        assertThat(firstToken).isNotBlank();
        assertThat(browser.cookie(AdminClient.SESSION_COOKIE)).isNull();

        // 2. the browser already holds a session id from before the login (made on the server, as fixation would).
        String before = createServerSession();
        browser.setCookie(AdminClient.SESSION_COOKIE, before);

        // 3. login: 204, a different session id, hardened cookie, the token rotated, the idle timeout set.
        AdminClient.Reply login = browser.login(AdminTestCredentials.username(), AdminTestCredentials.password());
        assertThat(login.status()).isEqualTo(204);
        String session = browser.cookie(AdminClient.SESSION_COOKIE);
        assertThat(session).isNotBlank().isNotEqualTo(before);
        assertThat(login.setCookie("JSESSIONID")).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        assertThat(browser.xsrf()).isNotBlank().isNotEqualTo(firstToken);
        assertThat(findServerSession(session).getMaxInactiveInterval()).isEqualTo(28_800);

        // 4. an authenticated call: the cookie alone is a read; a mutation also needs the XSRF header.
        AdminClient.Reply who = browser.bootstrap();
        assertThat(who.body()).isEqualTo("{\"authenticated\":true}");
        assertThat(browser.get(AdminClient.PROBE_PATH).status()).as("authenticated read, nothing mapped").isEqualTo(404);
        assertThat(browser.postWithToken(AdminClient.PROBE_PATH).status()).as("cookie + token").isEqualTo(404);
        AdminClient.Reply withoutToken = browser.send("POST", AdminClient.PROBE_PATH, null, null, Map.of());
        assertThat(withoutToken.status()).as("cookie, no token").isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(withoutToken.body(), withoutToken.header("x-request-id"),
                "admin-error-forbidden.json", AdminClient.PROBE_PATH);

        // 5. logout with the token.
        AdminClient.Reply logout = browser.logout();
        assertThat(logout.status()).isEqualTo(204);
        assertThat(findServerSession(session)).isNull();

        // 6. the old cookie is worth nothing, on a probe and on the bootstrap.
        AdminClient thief = newClient();
        thief.setCookie(AdminClient.SESSION_COOKIE, session);
        AdminClient.Reply replay = thief.get(AdminClient.PROBE_PATH);
        assertThat(replay.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(replay.body(), replay.header("x-request-id"),
                "admin-error-unauthenticated.json", AdminClient.PROBE_PATH);
        assertThat(thief.bootstrap().body()).isEqualTo("{\"authenticated\":false}");
    }
}
