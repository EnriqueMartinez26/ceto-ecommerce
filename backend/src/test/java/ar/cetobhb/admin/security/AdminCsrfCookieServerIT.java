package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The CSRF cookie is {@code __Host-XSRF-TOKEN} (review fix batch 7): the {@code __Host-} prefix makes a browser accept
 * it only when it is {@code Secure}, has {@code Path=/} and carries no {@code Domain}, so a sibling subdomain cannot
 * plant one. The header the SPA echoes stays {@code X-XSRF-TOKEN}. The token is not bound to the session (stateless
 * double submit): rotation only changes the cookie.
 */
class AdminCsrfCookieServerIT extends AbstractAdminServerIT {

    private static final String COOKIE = "__Host-XSRF-TOKEN";

    @Test
    void theHelperUsesTheHostPrefixedName() {
        assertThat(AdminClient.CSRF_COOKIE).isEqualTo(COOKIE);
    }

    @Test
    void theBootstrapSetsAHostPrefixedCookieWithNoDomainPathRootAndNoHttpOnly() throws Exception {
        AdminClient client = newClient();

        AdminClient.Reply reply = client.bootstrap();

        String cookie = reply.setCookie(COOKIE);
        assertThat(cookie).isNotNull().contains("Secure").contains("Path=/").contains("SameSite=Strict")
                .doesNotContain("Domain").doesNotContain("HttpOnly");
        assertThat(cookie).doesNotContain("Path=/api");
        assertThat(reply.setCookies()).as("only the prefixed cookie is set")
                .noneMatch(line -> line.startsWith("XSRF-TOKEN="));
    }

    @Test
    void theLoginRotationAndTheLogoutExpiryUseTheSameHostPrefixedCookie() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply login = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());
        String rotated = login.setCookie(COOKIE);
        AdminClient.Reply logout = client.logout();

        assertThat(rotated).isNotNull().contains("Secure").contains("Path=/").doesNotContain("Domain");
        assertThat(logout.setCookies()).anyMatch(line -> line.startsWith(COOKIE + "="));
        assertThat(logout.setCookies()).noneMatch(line -> line.startsWith("XSRF-TOKEN="));
    }

    @Test
    void theHeaderStaysXXsrfTokenAndTheEchoedHostPrefixedCookieIsAccepted() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        String token = client.xsrf();

        AdminClient.Reply withToken = client.send("POST", AdminClient.PROBE_PATH, null, null,
                Map.of("X-XSRF-TOKEN", token));

        assertThat(withToken.status()).as("past CSRF, then 401 for the anonymous caller").isEqualTo(401);
    }

    @Test
    void theOldUnprefixedCookieNameIsNotAToken() throws Exception {
        AdminClient client = newClient();
        client.clearCookies();
        client.setCookie("XSRF-TOKEN", "planted-token");

        AdminClient.Reply reply = client.send("POST", AdminClient.PROBE_PATH, null, null,
                Map.of("X-XSRF-TOKEN", "planted-token"));

        assertThat(reply.status()).as("a double submit with the old cookie name is refused").isEqualTo(403);
    }
}
