package ar.cetobhb.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One browser, for tests that talk to the real server: a JDK {@link HttpClient} with NO cookie handler (the JDK jar
 * would drop {@code Secure} cookies on plain HTTP) and a cookie jar of its own that follows {@code Set-Cookie} by hand,
 * deleting a cookie the server expires. Every client has its own {@code X-Forwarded-For} address, so a per-client
 * limiter never couples two clients (the test profile trusts only 127.0.0.1 as a proxy).
 *
 * <p>"Authenticated probe" (tasks X4): {@code GET /api/admin/<unmapped>} answers 404 when the session is valid (Security
 * let it through and no handler exists) and 401 when it is not.
 */
public final class AdminClient {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicInteger NEXT = new AtomicInteger();

    public static final String SESSION_COOKIE = "JSESSIONID";
    public static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
    public static final String PROBE_PATH = "/api/admin/zzz-unmapped-probe";

    /** One exchange: status, body, every {@code Set-Cookie} line and the headers (names lower-cased). */
    public record Reply(int status, String body, List<String> setCookies, Map<String, List<String>> headers) {

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        /** The {@code Set-Cookie} line of the named cookie, or null. */
        public String setCookie(String name) {
            return setCookies.stream().filter(line -> line.startsWith(name + "=")).findFirst().orElse(null);
        }
    }

    private final int port;
    private final String forwardedFor;
    private final Map<String, String> jar = new LinkedHashMap<>();

    public AdminClient(int port) {
        this.port = port;
        int n = NEXT.getAndIncrement();
        this.forwardedFor = "198." + (20 + ((n >> 16) & 0x01)) + "." + ((n >> 8) & 0xff) + "." + (n & 0xff);
    }

    /**
     * A browser that comes from the given client address (sent as {@code X-Forwarded-For}), so several clients can
     * share one address and one limiter bucket.
     */
    public AdminClient(int port, String forwardedFor) {
        this.port = port;
        this.forwardedFor = forwardedFor;
    }

    /** The client address this browser presents to the application. */
    public String forwardedFor() {
        return forwardedFor;
    }

    public Map<String, String> cookies() {
        return jar;
    }

    public String cookie(String name) {
        return jar.get(name);
    }

    /** Puts a cookie in the jar as if the server had set it. */
    public void setCookie(String name, String value) {
        jar.put(name, value);
    }

    public void clearCookies() {
        jar.clear();
    }

    public String xsrf() {
        synchronized (jar) {
            return jar.get(CSRF_COOKIE);
        }
    }

    public Reply get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, null, Map.of());
    }

    /** A request with no body, with the current XSRF cookie echoed in {@code X-XSRF-TOKEN} when there is one. */
    public Reply postWithToken(String path) throws IOException, InterruptedException {
        return send("POST", path, null, null, csrfHeader());
    }

    public Reply send(String method, String path, String contentType, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                .header("X-Forwarded-For", forwardedFor);
        synchronized (jar) {
            if (!jar.isEmpty()) {
                StringBuilder cookie = new StringBuilder();
                jar.forEach((name, value) -> cookie.append(cookie.isEmpty() ? "" : "; ").append(name).append('=')
                        .append(value));
                request.header("Cookie", cookie.toString());
            }
        }
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        List<String> setCookies = response.headers().allValues("set-cookie");
        absorb(setCookies);
        Map<String, List<String>> all = new TreeMap<>();
        response.headers().map().forEach((k, v) -> all.put(k.toLowerCase(Locale.ROOT), v));
        return new Reply(response.statusCode(), response.body(), List.copyOf(setCookies), all);
    }

    /** {@code POST /api/admin/login}, form-encoded, with the CSRF header taken from the jar (when it has the cookie). */
    public Reply login(String username, String password) throws IOException, InterruptedException {
        return loginWithHeaders(username, password, csrfHeader());
    }

    public Reply loginWithHeaders(String username, String password, Map<String, String> headers)
            throws IOException, InterruptedException {
        return send("POST", "/api/admin/login", "application/x-www-form-urlencoded", form(username, password), headers);
    }

    public Reply logout() throws IOException, InterruptedException {
        return postWithToken("/api/admin/logout");
    }

    /** {@code GET /api/admin/session}: the bootstrap, which also (re)issues the XSRF cookie. */
    public Reply bootstrap() throws IOException, InterruptedException {
        return get("/api/admin/session");
    }

    public boolean isAuthenticated() throws IOException, InterruptedException {
        int status = get(PROBE_PATH).status();
        if (status != 404 && status != 401) {
            throw new AssertionError("the authenticated probe answered " + status);
        }
        return status == 404;
    }

    public Map<String, String> csrfHeader() {
        String token = xsrf();
        return token == null ? Map.of() : Map.of("X-XSRF-TOKEN", token);
    }

    public static String form(String username, String password) {
        return "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
    }

    /** The jar is shared by the threads of a burst (a double tap of one browser): every access holds its monitor. */
    private void absorb(List<String> setCookies) {
        synchronized (jar) {
            absorbLocked(setCookies);
        }
    }

    private void absorbLocked(List<String> setCookies) {
        for (String line : setCookies) {
            String pair = line.split(";", 2)[0];
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if (expires(line) || value.isEmpty()) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
    }

    private static boolean expires(String setCookieLine) {
        for (String attribute : new ArrayList<>(List.of(setCookieLine.split(";")))) {
            String a = attribute.trim().toLowerCase(Locale.ROOT);
            if (a.equals("max-age=0") || a.startsWith("max-age=-")) {
                return true;
            }
        }
        return false;
    }
}
