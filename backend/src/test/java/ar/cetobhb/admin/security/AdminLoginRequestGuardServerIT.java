package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.CountingPasswordEncoder;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The login request guard (review fix batch 7), on the real server because the behaviour is the container's: the
 * query string, the {@code Content-Type} and the repeated parameters are refused BEFORE the CSRF filter and before the
 * login filter, so no BCrypt work runs and no session is created. The login contract is a body only, urlencoded, with
 * one value per field.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminLoginRequestGuardServerIT extends AbstractAdminServerIT {

    private static final String LOGIN = "/api/admin/login";
    private static final String FORM = "application/x-www-form-urlencoded";

    private AdminClient bootstrapped() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        return client;
    }

    private void assertRefusedWithoutWork(AdminClient client, AdminClient.Reply reply, int hashesBefore)
            throws Exception {
        assertThat(CountingPasswordEncoder.matches()).as("no BCrypt work").isEqualTo(hashesBefore);
        assertThat(reply.setCookie("JSESSIONID")).as("no session").isNull();
        assertThat(client.isAuthenticated()).isFalse();
    }

    @ParameterizedTest(name = "query string {0}")
    @ValueSource(strings = {"?x=1", "?username=nobody", "?password=whatever", "?username=a&username=b"})
    void aQueryStringOnTheLoginIs400AndRunsNoHashEvenWithValidCredentials(String query) throws Exception {
        AdminClient client = bootstrapped();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.send("POST", LOGIN + query, FORM,
                AdminClient.form(AdminTestCredentials.username(), AdminTestCredentials.password()),
                client.csrfHeader());

        assertThat(reply.status()).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-rejected-request.json", null);
        assertRefusedWithoutWork(client, reply, before);
    }

    @ParameterizedTest(name = "content type {0}")
    @ValueSource(strings = {"application/json", "multipart/form-data; boundary=xyz", "text/plain",
            "application/x-www-form-urlencodedX", "application/xml"})
    void aContentTypeThatIsNotUrlencodedIs415AndRunsNoHash(String contentType) throws Exception {
        AdminClient client = bootstrapped();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.send("POST", LOGIN, contentType,
                AdminClient.form(AdminTestCredentials.username(), AdminTestCredentials.password()),
                client.csrfHeader());

        assertThat(reply.status()).isEqualTo(415);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-unsupported-media-type.json", LOGIN);
        assertRefusedWithoutWork(client, reply, before);
    }

    @Test
    void aJsonBodyWithTheRightCredentialsIs415() throws Exception {
        AdminClient client = bootstrapped();
        int before = CountingPasswordEncoder.matches();
        String json = "{\"username\":\"" + AdminTestCredentials.username() + "\",\"password\":\""
                + AdminTestCredentials.password() + "\"}";

        AdminClient.Reply reply = client.send("POST", LOGIN, "application/json", json, client.csrfHeader());

        assertThat(reply.status()).isEqualTo(415);
        assertRefusedWithoutWork(client, reply, before);
    }

    @Test
    void aMultipartBodyWithTheRightCredentialsIs415() throws Exception {
        AdminClient client = bootstrapped();
        int before = CountingPasswordEncoder.matches();
        String multipart = "--xyz\r\nContent-Disposition: form-data; name=\"username\"\r\n\r\n"
                + AdminTestCredentials.username() + "\r\n--xyz\r\nContent-Disposition: form-data; name=\"password\""
                + "\r\n\r\n" + AdminTestCredentials.password() + "\r\n--xyz--\r\n";

        AdminClient.Reply reply = client.send("POST", LOGIN, "multipart/form-data; boundary=xyz", multipart,
                client.csrfHeader());

        assertThat(reply.status()).isEqualTo(415);
        assertRefusedWithoutWork(client, reply, before);
    }

    @Test
    void aLoginWithNoContentTypeAtAllIs415() throws Exception {
        AdminClient client = bootstrapped();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.send("POST", LOGIN, null, null, client.csrfHeader());

        assertThat(reply.status()).isEqualTo(415);
        assertRefusedWithoutWork(client, reply, before);
    }

    @Test
    void theContentTypeIsRefusedBeforeTheCsrfCheck() throws Exception {
        AdminClient client = newClient();

        AdminClient.Reply reply = client.send("POST", LOGIN, "application/json", "{}", Map.of());

        assertThat(reply.status()).isEqualTo(415);
    }

    @ParameterizedTest(name = "content type {0}")
    @ValueSource(strings = {"application/x-www-form-urlencoded; charset=UTF-8",
            "application/x-www-form-urlencoded;charset=utf-8", "Application/X-WWW-Form-Urlencoded"})
    void theUrlencodedContentTypeWithAnyCharsetParameterStillLogsIn(String contentType) throws Exception {
        AdminClient client = bootstrapped();

        AdminClient.Reply reply = client.send("POST", LOGIN, contentType,
                AdminClient.form(AdminTestCredentials.username(), AdminTestCredentials.password()),
                client.csrfHeader());

        assertThat(reply.status()).isEqualTo(204);
        assertThat(client.isAuthenticated()).isTrue();
    }

    @ParameterizedTest(name = "form with {0}")
    @ValueSource(strings = {"usernameTwice", "usernameTwiceRealLast", "passwordTwice", "passwordTwiceRealLast",
            "passwordMissing", "usernameMissing", "emptyBody"})
    void aFieldThatIsRepeatedOrMissingIs400AndRunsNoHash(String shape) throws Exception {
        String user = AdminTestCredentials.username();
        String pass = AdminTestCredentials.password();
        String body = switch (shape) {
            case "usernameTwice" -> "username=" + user + "&username=other&password=" + pass;
            case "usernameTwiceRealLast" -> "username=other&username=" + user + "&password=" + pass;
            case "passwordTwice" -> "username=" + user + "&password=" + pass + "&password=other";
            case "passwordTwiceRealLast" -> "username=" + user + "&password=wrong&password=" + pass;
            case "passwordMissing" -> "username=" + user;
            case "usernameMissing" -> "password=" + pass;
            default -> "";
        };
        AdminClient client = bootstrapped();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.send("POST", LOGIN, FORM, body, client.csrfHeader());

        assertThat(reply.status()).as(shape).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-rejected-request.json", null);
        assertRefusedWithoutWork(client, reply, before);
    }

    @Test
    void aBlankPasswordIsOneValueSoItIsTheNormalNeutral401() throws Exception {
        AdminClient client = bootstrapped();

        AdminClient.Reply reply = client.send("POST", LOGIN, FORM, "username=" + AdminTestCredentials.username()
                + "&password=", client.csrfHeader());

        assertThat(reply.status()).isEqualTo(401);
    }

    @Test
    void aMalformedPercentEncodingIsRefusedAndNeitherTheUsernameNorAnyPasswordFragmentIsLogged(
            CapturedOutput output) throws Exception {
        AdminClient client = bootstrapped();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.send("POST", LOGIN, FORM,
                "username=SentinelUser-5521&password=%zzSentinelPass-7731", client.csrfHeader());

        assertThat(reply.status()).isEqualTo(400);
        assertRefusedWithoutWork(client, reply, before);
        assertThat(reply.body() + reply.headers()).doesNotContain("SentinelUser-5521").doesNotContain("SentinelPass")
                .doesNotContain("%zz");
        assertThat(output.getAll()).doesNotContain("SentinelUser-5521").doesNotContain("SentinelPass-7731")
                .doesNotContain("SentinelPass").doesNotContain("%zz");
    }

    @Test
    void aBareQuestionMarkOnTheLoginIsRefusedToo() throws Exception {
        AdminClient client = bootstrapped();
        int before = CountingPasswordEncoder.matches();
        String form = AdminClient.form(AdminTestCredentials.username(), AdminTestCredentials.password());

        String reply = rawLoginPost(client, LOGIN + "?", form);

        assertThat(reply.lines().findFirst().orElse("")).startsWith("HTTP/1.1 400");
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before);
    }

    private String rawLoginPost(AdminClient client, String target, String form) throws Exception {
        String request = "POST " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nContent-Type: " + FORM + "\r\nContent-Length: " + form.length() + "\r\nConnection: close\r\n"
                + "Cookie: __Host-XSRF-TOKEN=" + client.xsrf() + "\r\nX-XSRF-TOKEN: " + client.xsrf() + "\r\n\r\n" + form;
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aMalformedPercentEncodingInTheQueryStringIsRefusedAndNotLogged(CapturedOutput output) throws Exception {
        AdminClient client = bootstrapped();
        String form = AdminClient.form("SentinelUser-5521", "x");
        // The JDK client refuses to build a URI with a bad escape, so this one is written on a socket.
        String reply = rawLoginPost(client, LOGIN + "?password=%zzSentinelQuery-2210", form);

        assertThat(reply.lines().findFirst().orElse("")).startsWith("HTTP/1.1 400");
        assertThat(reply).doesNotContain("Sentinel").doesNotContain("%zz");
        assertThat(output.getAll()).doesNotContain("SentinelUser-5521").doesNotContain("SentinelQuery")
                .doesNotContain("%zz");
    }

    /** Pinned (2026-10-08): the login URL is POST-only; a GET is not a login page and reaches no handler. */
    @Test
    void anAnonymousGetOfTheLoginPathIsThe401ProblemAndReachesNoHandler() throws Exception {
        AdminClient client = newClient();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.get(LOGIN);

        assertThat(reply.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-unauthenticated.json", LOGIN);
        assertThat(reply.setCookie("JSESSIONID")).isNull();
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before);
    }
}
