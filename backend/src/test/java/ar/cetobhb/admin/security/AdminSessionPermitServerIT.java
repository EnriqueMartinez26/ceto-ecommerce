package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.ContractAssertions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The anonymous allowance of the chain is EXACTLY {@code GET /api/admin/session} (review fix batch 6): the method AND
 * the path, never a wider pattern and never any method. On the real server, an anonymous client with a valid XSRF
 * token (so only the missing login can stop it) gets 401 for every other method on that path and for the spellings of
 * the path that are not that path.
 */
class AdminSessionPermitServerIT extends AbstractAdminServerIT {

    private static final String SESSION = "/api/admin/session";

    @Test
    void theExactGetIsThePublicBootstrapAndSetsTheXsrfCookie() throws Exception {
        AdminClient client = newClient();

        AdminClient.Reply reply = client.get(SESSION);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo("{\"authenticated\":false}");
        assertThat(reply.setCookie("XSRF-TOKEN")).contains("Secure").contains("SameSite=Strict")
                .doesNotContain("HttpOnly");
        assertThat(reply.setCookie("JSESSIONID")).isNull();
        assertThat(client.cookie("JSESSIONID")).isNull();
        assertThat(client.isAuthenticated()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void everyOtherMethodOnTheSessionPathIsA401ForAnAnonymousClient(String method) throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply reply = client.send(method, SESSION,
                method.equals("HEAD") || method.equals("OPTIONS") ? null : "application/json",
                method.equals("HEAD") || method.equals("OPTIONS") ? null : "{}", client.csrfHeader());

        assertThat(reply.status()).as(method).isEqualTo(401);
        if (method.equals("HEAD")) {
            assertThat(reply.body()).isEmpty();
        } else {
            ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                    "admin-error-unauthenticated.json", SESSION);
        }
        assertThat(reply.header("allow")).isNull();
        assertThat(reply.header("access-control-allow-origin")).isNull();
    }

    /**
     * Every spelling of the path that is not the path, with the status it gets TODAY (pinned exactly, so a wider
     * allowance such as a `/api/admin/session/**` pattern, which would turn the first three into 404 from the missing
     * handler, is noticed): Security answers 401 to the ones it sees as admin paths, its firewall rejects `;`, and
     * Tomcat itself refuses an encoded slash.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"/api/admin/%73ession,401", "/api/admin/session/,401", "/api/admin/session/x,401",
            "/api/admin/Session,401", "/api/admin/session%2Fx,400", "/api/admin/session;x=1,400"})
    void everySpellingOfThePathThatIsNotThePathIsNeverTheBootstrap(String path, int expectedStatus) throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply reply = client.send("GET", path, null, null, Map.of());

        assertThat(reply.status()).as(path).isEqualTo(expectedStatus);
        assertThat(reply.body()).as(path).doesNotContain("authenticated");
        assertThat(reply.setCookie("JSESSIONID")).isNull();
    }

    @Test
    void theEncodedSpellingOfTheSessionPathIsA401WithTheProblemBody() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply reply = client.get("/api/admin/%73ession");

        assertThat(reply.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-unauthenticated.json", "/api/admin/%73ession");
    }
}
