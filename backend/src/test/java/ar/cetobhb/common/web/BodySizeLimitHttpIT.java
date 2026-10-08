package ar.cetobhb.common.web;

import static ar.cetobhb.support.ContractAssertions.assertMatchesGolden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.support.AbstractHttpIT;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Body limit through the whole filter chain on the MOCK environment (REQ-ABU body limit): a declared length over
 * 16384 bytes is answered 413 before anything is read. The streamed (chunked) path needs a real socket and is in
 * {@code BodySizeLimitServerIT}.
 */
class BodySizeLimitHttpIT extends AbstractHttpIT {

    private static final int MAX = 16_384;

    @Autowired
    private JdbcTemplate jdbc;

    private static MockHttpServletRequestBuilder checkout(byte[] body) {
        return post("/api/checkout/orders").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body);
    }

    /** A JSON object with exactly {@code size} bytes: one unknown member padded with ASCII letters. */
    private static byte[] jsonOfSize(int size) {
        String prefix = "{\"x\":\"";
        String suffix = "\"}";
        String padding = "a".repeat(size - prefix.length() - suffix.length());
        byte[] bytes = (prefix + padding + suffix).getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(size);
        return bytes;
    }

    @Test
    void aBodyOf20000BytesIsRejectedWith413AndTheGoldenProblem() throws Exception {
        MockHttpServletResponse response = send(checkout(jsonOfSize(20_000)));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Connection")).isEqualTo("close");
        assertMatchesGolden(response, "error-payload-too-large.json");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
    }

    @Test
    void oneByteOverTheLimitIsRejectedAndExactlyTheLimitIsNot() throws Exception {
        assertThat(send(checkout(jsonOfSize(MAX + 1))).getStatus()).isEqualTo(413);

        MockHttpServletResponse atTheLimit = send(checkout(jsonOfSize(MAX)));

        // Passed the filter: the unknown member then fails the strict parser as an ordinary 400.
        assertThat(atTheLimit.getStatus()).isEqualTo(400);
    }

    @Test
    void theRequestIdOfAnOversizedRequestIsOnTheResponseHeaderAndTheBody() throws Exception {
        MockHttpServletResponse response = send(checkout(jsonOfSize(MAX + 1)));

        assertThat(response.getHeader("X-Request-Id")).isNotNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"requestId\":\"" + response.getHeader("X-Request-Id") + "\"");
    }

    @Test
    void anOversizedBodyIsRejectedBeforeTheRateLimiterSpendsAToken() throws Exception {
        // 11 oversized attempts from one client are all 413 (never 429): the limiter is behind the filter.
        for (int i = 0; i < 11; i++) {
            MockHttpServletResponse response = mvc.perform(checkout(jsonOfSize(MAX + 1)).with(request -> {
                request.setRemoteAddr("203.0.113.77");
                return request;
            })).andReturn().getResponse();
            assertThat(response.getStatus()).as("attempt %d", i + 1).isEqualTo(413);
        }
        // ... and the client still has its whole budget for real attempts.
        MockHttpServletResponse real = mvc.perform(checkout("{".getBytes(StandardCharsets.UTF_8)).with(request -> {
            request.setRemoteAddr("203.0.113.77");
            return request;
        })).andReturn().getResponse();
        assertThat(real.getStatus()).isEqualTo(400);
    }

    @Test
    void aDeeplyNestedBodyIsNeverA500() throws Exception {
        // 10000 levels = 20000 bytes: over the limit, so the filter answers before the parser sees the depth.
        String tooBig = "[".repeat(10_000) + "]".repeat(10_000);
        assertThat(send(checkout(tooBig.getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(413);

        // 8000 levels = 16000 bytes: fits, so the depth constraint of the strict parser rejects it as malformed.
        String fits = "[".repeat(8_000) + "]".repeat(8_000);
        MockHttpServletResponse response = send(checkout(fits.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("MALFORMED_REQUEST");
    }
}
