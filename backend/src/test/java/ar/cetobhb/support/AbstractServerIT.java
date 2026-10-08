package ar.cetobhb.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Base for tests that need a real HTTP server: a random port ({@code local.server.port}, verification U-11) and the
 * JDK {@link HttpClient} talking to 127.0.0.1, so no dependency beyond the JDK is needed. Use it only where MockMvc
 * cannot reach the behaviour (real concurrency, container-level features such as the remote IP valve or chunked
 * bodies); contract tests stay on {@link AbstractHttpIT}.
 *
 * <p>This is a second Spring context next to the MOCK one, each with its own Hikari pool (30 in the test profile);
 * both stay well below the Postgres default of 100 connections.
 *
 * <p>Per-IP limiter (added in a later slice): every request from this client comes from 127.0.0.1, so the burst
 * tests would hit it. When the limiter exists, this class (or the test profile) must raise its burst for these tests;
 * forwarded headers cannot be used to vary the address without trusted-proxy configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractServerIT extends AbstractPostgresIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    protected int port;

    /** Status and text body of one HTTP exchange. */
    protected record Reply(int status, String body) {
    }

    protected URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** POST of a JSON checkout body with the given {@code Idempotency-Key}; failures to talk HTTP are exceptions. */
    protected Reply post(String idempotencyKey, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/checkout/orders"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }
}
