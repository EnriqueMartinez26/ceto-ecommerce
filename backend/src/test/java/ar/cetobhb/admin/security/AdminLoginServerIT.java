package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.CountingPasswordEncoder;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The login contract on the real server (REQ-AUTH-02, -03, -04, -05): form-encoded fields, 204 with a fresh session
 * cookie, one neutral 401 for everything that is not a valid credential, CSRF enforced, and no hashing work for input
 * the application can refuse beforehand.
 */
class AdminLoginServerIT extends AbstractAdminServerIT {

    private static final String LOGIN = "/api/admin/login";

    @Test
    void bootstrapThenLoginWithTheXsrfHeaderIs204WithNoBodyAndAHardenedSessionCookie() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply reply = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(reply.status()).isEqualTo(204);
        assertThat(reply.body()).isEmpty();
        String cookie = reply.setCookie("JSESSIONID");
        assertThat(cookie).isNotNull().contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/");
        assertThat(client.isAuthenticated()).isTrue();
        assertThat(reply.header("cache-control")).contains("no-store");
    }

    @Test
    void theLoginResponseNeverCarriesCredentialsOrTheHash() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply reply = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        String everything = reply.headers().toString() + reply.body();
        assertThat(everything).doesNotContain(AdminTestCredentials.password())
                .doesNotContain(AdminTestCredentials.passwordHash());
    }

    @Test
    void loginWithoutACsrfTokenIs403ForbiddenAndOpensNoSession() throws Exception {
        AdminClient client = newClient();

        AdminClient.Reply reply = client.loginWithHeaders(AdminTestCredentials.username(),
                AdminTestCredentials.password(), Map.of());

        assertThat(reply.status()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-forbidden.json", LOGIN);
        assertThat(reply.setCookie("JSESSIONID")).isNull();
        assertThat(client.isAuthenticated()).isFalse();
    }

    @Test
    void loginWithAGarbageCsrfTokenIs403ForbiddenAndOpensNoSession() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply reply = client.loginWithHeaders(AdminTestCredentials.username(),
                AdminTestCredentials.password(), Map.of("X-XSRF-TOKEN", "garbage"));

        assertThat(reply.status()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-forbidden.json", LOGIN);
        assertThat(reply.setCookie("JSESSIONID")).isNull();
        assertThat(client.isAuthenticated()).isFalse();
    }

    @Test
    void anUnknownUserAndAWrongPasswordGetTheSameNeutral401() throws Exception {
        AdminClient unknown = newClient();
        unknown.bootstrap();
        AdminClient wrong = newClient();
        wrong.bootstrap();

        AdminClient.Reply nobody = unknown.login("nobody", "whatever-password");
        AdminClient.Reply badPassword = wrong.login(AdminTestCredentials.username(), "not-the-password");

        assertThat(nobody.status()).isEqualTo(401);
        assertThat(badPassword.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(nobody.body(), nobody.header("x-request-id"),
                "admin-error-invalid-credentials.json", LOGIN);
        ContractAssertions.assertMatchesGoldenForPath(badPassword.body(), badPassword.header("x-request-id"),
                "admin-error-invalid-credentials.json", LOGIN);
        assertThat(comparableHeaders(nobody)).isEqualTo(comparableHeaders(badPassword));
        assertThat(nobody.setCookie("JSESSIONID")).isNull();
        assertThat(badPassword.setCookie("JSESSIONID")).isNull();
        assertThat(unknown.isAuthenticated()).isFalse();
        assertThat(wrong.isAuthenticated()).isFalse();
    }

    @Test
    void theNeutralBodyNamesNeitherTheUsernameNorThePassword() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();

        AdminClient.Reply reply = client.login("sentinel-user-4242", "sentinel-pass-4242");

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.body() + reply.headers()).doesNotContain("sentinel-user-4242").doesNotContain("sentinel-pass-4242");
    }

    @Test
    void theLoginResponseRotatesTheXsrfCookieAndTheNewTokenWorksForTheNextMutation() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        String before = client.xsrf();
        assertThat(before).isNotBlank();

        AdminClient.Reply login = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(login.status()).isEqualTo(204);
        // Observed (2026-10-08): Security replaces the token on authentication and the 204 itself carries the new
        // cookie, so the client does not need another bootstrap call before its first POST.
        String rotated = login.setCookie("XSRF-TOKEN");
        assertThat(rotated).as("the login response sets a new token cookie").isNotNull().contains("Secure")
                .contains("SameSite=Strict").doesNotContain("HttpOnly");
        assertThat(client.xsrf()).isNotBlank().isNotEqualTo(before);
        assertThat(client.postWithToken(AdminClient.PROBE_PATH).status())
                .as("the new token passes CSRF and the logged-in client reaches the (unmapped) path").isEqualTo(404);
        assertThat(client.send("POST", AdminClient.PROBE_PATH, null, null, Map.of()).status())
                .as("without the header a mutation is refused").isEqualTo(403);
    }

    @Test
    void aOneHundredThousandCharacterPasswordIs413AndCostsNoHash() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.login(AdminTestCredentials.username(), "p".repeat(100_000));

        assertThat(reply.status()).isEqualTo(413);
        assertThat(CountingPasswordEncoder.matches()).as("no BCrypt work").isEqualTo(before);
        assertThat(reply.setCookie("JSESSIONID")).isNull();
    }

    @ParameterizedTest(name = "username of {0} chars")
    @ValueSource(ints = {129, 1000})
    void aUsernameOverTheCapIsTheNeutral401AndCostsNoHash(int length) throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.login("u".repeat(length), "short-password");

        assertThat(reply.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-invalid-credentials.json", LOGIN);
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before);
    }

    @ParameterizedTest(name = "password of {0} chars")
    @ValueSource(ints = {129, 1000})
    void aPasswordOverTheCapIsTheNeutral401AndCostsNoHashEvenForTheRealUsername(int length) throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        int before = CountingPasswordEncoder.matches();

        AdminClient.Reply reply = client.login(AdminTestCredentials.username(), "p".repeat(length));

        assertThat(reply.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-invalid-credentials.json", LOGIN);
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before);
    }

    @Test
    void aPasswordWithinTheCapButOver72BytesIsTheNeutral401AndCostsNoHash() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        int before = CountingPasswordEncoder.matches();

        // 100 characters, 200 bytes in UTF-8: inside the field cap, past what BCrypt can read.
        AdminClient.Reply reply = client.login(AdminTestCredentials.username(), "é".repeat(100));

        assertThat(reply.status()).isEqualTo(401);
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before);
    }

    @Test
    void aWrongPasswordInsideTheCapsDoesCostOneHashSoTheCounterIsNotVacuous() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        int before = CountingPasswordEncoder.matches();

        client.login(AdminTestCredentials.username(), "not-the-password");

        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before + 1);
    }

    @ParameterizedTest(name = "with a valid token: {0}")
    @ValueSource(booleans = {true, false})
    void aChunkedHundredKilobyteLoginFormIsA4xxAndNeverA5xxOrAHash(boolean withToken) throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        int before = CountingPasswordEncoder.matches();

        String reply = chunkedLogin(client, 100_000, withToken);

        String statusLine = reply.lines().findFirst().orElse("");
        assertThat(statusLine).as(statusLine).matches("HTTP/1\\.1 4\\d\\d.*");
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before);
    }

    private String chunkedLogin(AdminClient client, int passwordLength, boolean withToken) throws IOException {
        byte[] form = ("username=" + AdminTestCredentials.username() + "&password=" + "x".repeat(passwordLength))
                .getBytes(StandardCharsets.US_ASCII);
        StringBuilder request = new StringBuilder("POST " + LOGIN + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nContent-Type: application/x-www-form-urlencoded\r\nTransfer-Encoding: chunked\r\n"
                + "Connection: close\r\nCookie: XSRF-TOKEN=" + client.xsrf() + "\r\n");
        if (withToken) {
            request.append("X-XSRF-TOKEN: ").append(client.xsrf()).append("\r\n");
        }
        request.append("\r\n");
        for (int offset = 0; offset < form.length; offset += 4096) {
            int length = Math.min(4096, form.length - offset);
            request.append(Integer.toHexString(length)).append("\r\n")
                    .append(new String(form, offset, length, StandardCharsets.US_ASCII)).append("\r\n");
        }
        request.append("0\r\n\r\n");
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(10_000);
            try {
                socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (IOException serverAlreadyAnswered) {
                // The server may close once it has answered; the reply is read below either way.
            }
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Headers that must be equal across two failures: everything except what is per request or per client. */
    private static Map<String, java.util.List<String>> comparableHeaders(AdminClient.Reply reply) {
        Map<String, java.util.List<String>> headers = new TreeMap<>(reply.headers());
        headers.remove("date");
        headers.remove("x-request-id");
        headers.remove("set-cookie");
        return headers;
    }
}
