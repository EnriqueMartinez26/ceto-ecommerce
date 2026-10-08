package ar.cetobhb.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Base for tests that need a real HTTP server: a random port ({@code local.server.port}, verification U-11) and the
 * JDK {@link HttpClient} talking to 127.0.0.1, so no dependency beyond the JDK is needed. Use it only where MockMvc
 * cannot reach the behaviour (real concurrency, container-level features such as the remote IP valve or chunked
 * bodies); contract tests stay on {@link AbstractHttpIT}.
 *
 * <p>This is a second Spring context next to the MOCK one, each with its own Hikari pool (at most 30 connections in
 * the test profile, shrinking to 2 idle ones); cached contexts therefore stay well below the Postgres default of 100
 * connections.
 *
 * <p>Per-client limiter: the test profile trusts only the IPv4 loopback as a proxy, so {@link #post} gives every
 * request its own client address through {@code X-Forwarded-For}; without it the 20-request bursts would all come
 * from 127.0.0.1 and hit the limiter. Tests of the limiter itself use {@link #postFrom}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractServerIT extends AbstractPostgresIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger();

    @LocalServerPort
    protected int port;

    /** Status and text body of one HTTP exchange. */
    protected record Reply(int status, String body) {
    }

    /**
     * POST of a JSON checkout body with the given {@code Idempotency-Key}, as a client of its own (a fresh
     * {@code X-Forwarded-For} per call, from the benchmarking range 198.18.0.0/15); failures to talk HTTP are
     * exceptions.
     */
    protected Reply post(String idempotencyKey, String body) throws IOException, InterruptedException {
        int n = NEXT_CLIENT.getAndIncrement();
        String client = "198." + (18 + ((n >> 16) & 0x01)) + "." + ((n >> 8) & 0xff) + "." + (n & 0xff);
        return postFrom("127.0.0.1", client, idempotencyKey, body);
    }

    /**
     * POST to the given host (an IP literal, brackets included for IPv6) with an optional raw
     * {@code X-Forwarded-For} value, exactly as given.
     */
    protected Reply postFrom(String host, String forwardedFor, String idempotencyKey, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port
                        + "/api/checkout/orders"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }
}
