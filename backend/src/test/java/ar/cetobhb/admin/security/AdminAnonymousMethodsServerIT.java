package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import adminprobe.AdminProbeController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An anonymous client gets a 401 for EVERY HTTP method on an admin path, on the real server: GET, POST, PUT, PATCH and
 * DELETE (with a valid CSRF token, so only the missing login can stop them), HEAD (the container strips the body) and
 * OPTIONS (Security does no CORS preflight handling here, so it is the same 401 and never a 2xx with content). The
 * paths are a route that accepts any method (so a method gap in the chain would reach the handler and print the
 * sentinel) and a path nothing maps.
 */
class AdminAnonymousMethodsServerIT extends AbstractAdminProbeServerIT {

    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private static final Pattern XSRF = Pattern.compile("XSRF-TOKEN=([^;]+)");

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private String csrfToken() throws Exception {
        HttpRequest bootstrap = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/zzz-bootstrap"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = CLIENT.send(bootstrap, HttpResponse.BodyHandlers.ofString());
        for (String cookie : response.headers().allValues("set-cookie")) {
            Matcher match = XSRF.matcher(cookie);
            if (match.find()) {
                return match.group(1);
            }
        }
        throw new AssertionError("the anonymous admin response did not set the XSRF-TOKEN cookie");
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token).header("X-XSRF-TOKEN", token);
        if (List.of("POST", "PUT", "PATCH", "DELETE").contains(method)) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString("{}"));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/admin/probe-any/7", "/api/admin/probe-any/notanumber", "/api/admin/zzz"})
    void everyMethodIsA401ForAnAnonymousClientAndNeverReachesTheHandler(String path) throws Exception {
        String token = csrfToken();

        for (String method : METHODS) {
            HttpResponse<String> reply = call(method, path, token);

            assertThat(reply.statusCode()).as("%s %s", method, path).isEqualTo(401);
            assertThat(reply.body()).as("%s %s", method, path).doesNotContain(AdminProbeController.SENTINEL);
            if (method.equals("HEAD")) {
                assertThat(reply.body()).as("HEAD has no body").isEmpty();
            } else {
                assertThat(reply.body()).as("%s %s", method, path).contains("\"code\":\"UNAUTHENTICATED\"");
            }
            assertThat(reply.headers().firstValue("access-control-allow-origin")).isEmpty();
            assertThat(reply.headers().firstValue("allow")).as("no method list is advertised").isEmpty();
        }
    }

    @Test
    void optionsIsNeverA2xxWithContentOnAnAdminPath() throws Exception {
        HttpResponse<String> reply = call("OPTIONS", "/api/admin/probe-any/7", csrfToken());

        // Recorded behaviour (2026-10-08): a plain 401 problem, no Allow header, no CORS headers.
        assertThat(reply.statusCode()).isEqualTo(401);
        assertThat(reply.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT))
                .startsWith("application/problem+json");
    }
}
