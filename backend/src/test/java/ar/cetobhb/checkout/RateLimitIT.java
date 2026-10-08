package ar.cetobhb.checkout;

import static ar.cetobhb.support.ContractAssertions.assertMatchesGolden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.support.AbstractHttpIT;
import ar.cetobhb.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Per-client limiter of {@code POST /api/checkout/orders} (REQ-ABU-02) with the shipped defaults: 10 attempts at once,
 * one more every 360 seconds. Each test owns its client address, so the limiter state shared by the cached context
 * never links two tests. Attempts send an unreadable body: the interceptor must answer before the body is parsed, so a
 * 400 means "passed the limiter" and a 429 means "stopped by it", without touching the database.
 */
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class RateLimitIT extends AbstractHttpIT {

    private static final String PATH = "/api/checkout/orders";
    private static final String HANDLER_LOGGER = "ar.cetobhb.common.controller.ApiExceptionHandler";
    private static final int BURST = 10;

    @Autowired
    private MutableClock clock;

    @Autowired
    private LoggingSystem logging;

    @BeforeEach
    void logHandlerAtDebug() {
        logging.setLogLevel(HANDLER_LOGGER, LogLevel.DEBUG);
    }

    @AfterEach
    void restoreHandlerLevel() {
        logging.setLogLevel(HANDLER_LOGGER, null);
    }

    private MockHttpServletResponse attempt(String clientAddress, String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8))
                        .with(request -> {
                            request.setRemoteAddr(clientAddress);
                            return request;
                        }))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse attempt(String clientAddress) throws Exception {
        return attempt(clientAddress, "{");
    }

    private void spend(String clientAddress, int attempts) throws Exception {
        for (int i = 0; i < attempts; i++) {
            assertThat(attempt(clientAddress).getStatus()).as("attempt %d", i + 1).isEqualTo(400);
        }
    }

    @Test
    void theEleventhAttemptIsRejectedWithTheRateLimitedProblem() throws Exception {
        spend("203.0.113.50", BURST);

        MockHttpServletResponse limited = attempt("203.0.113.50");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentType()).startsWith("application/problem+json");
        assertThat(limited.getHeader("Retry-After")).isEqualTo("360");
        assertThat(limited.getHeader("Cache-Control")).isEqualTo("no-store");
        assertMatchesGolden(limited, "error-rate-limited.json");
    }

    @Test
    void retryAfterShrinksAsTimePassesAndIsAWholeNumberOfSeconds() throws Exception {
        spend("203.0.113.51", BURST);
        clock.set(MutableClock.START.plusMillis(100_500));

        MockHttpServletResponse limited = attempt("203.0.113.51");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("260");
    }

    @Test
    void oneTokenComesBackAfterTheRefillInterval() throws Exception {
        spend("203.0.113.52", BURST);
        assertThat(attempt("203.0.113.52").getStatus()).isEqualTo(429);

        clock.set(MutableClock.START.plus(Duration.ofSeconds(360)));

        assertThat(attempt("203.0.113.52").getStatus()).isEqualTo(400);
        assertThat(attempt("203.0.113.52").getStatus()).isEqualTo(429);
    }

    @Test
    void anExhaustedClientDoesNotAffectAnother() throws Exception {
        spend("203.0.113.53", BURST);
        assertThat(attempt("203.0.113.53").getStatus()).isEqualTo(429);

        assertThat(attempt("203.0.113.54").getStatus()).isEqualTo(400);
    }

    @Test
    void ipv6AddressesOfTheSame64ShareOneBucket() throws Exception {
        spend("2001:db8:aa:bb::1", BURST);

        assertThat(attempt("2001:db8:aa:bb:ffff:1:2:3").getStatus()).isEqualTo(429);
        assertThat(attempt("2001:db8:aa:bc::1").getStatus()).isEqualTo(400);
    }

    @Test
    void theLimiterAnswersBeforeTheBodyIsRead() throws Exception {
        spend("203.0.113.55", BURST);

        assertThat(attempt("203.0.113.55", "{").getStatus()).isEqualTo(429);
        assertThat(attempt("203.0.113.55", "{}").getStatus()).isEqualTo(429);
        assertThat(attempt("203.0.113.55", "not json at all").getStatus()).isEqualTo(429);
    }

    @Test
    void everyAttemptCountsEvenWhenItFailsValidation() throws Exception {
        for (int i = 0; i < BURST; i++) {
            assertThat(attempt("203.0.113.56", "{}").getStatus()).isEqualTo(400);
        }

        assertThat(attempt("203.0.113.56", "{}").getStatus()).isEqualTo(429);
    }

    @Test
    void onlyThePostSpendsAnAttempt() throws Exception {
        for (int i = 0; i < BURST * 2; i++) {
            MockHttpServletResponse options = mvc.perform(options(PATH).with(request -> {
                request.setRemoteAddr("203.0.113.59");
                return request;
            })).andReturn().getResponse();
            assertThat(options.getStatus()).as("options %d", i + 1).isEqualTo(200);
        }

        spend("203.0.113.59", BURST);
    }

    @Test
    void storefrontReadsAreNotRateLimited() throws Exception {
        for (int i = 0; i < BURST * 3; i++) {
            MockHttpServletResponse read = mvc.perform(get("/api/store/products").accept(MediaType.APPLICATION_JSON)
                    .with(request -> {
                        request.setRemoteAddr("203.0.113.57");
                        return request;
                    })).andReturn().getResponse();
            assertThat(read.getStatus()).as("read %d", i + 1).isEqualTo(200);
        }
    }

    @Test
    void theRejectionNeverLogsTheClientAddress(CapturedOutput output) throws Exception {
        spend("203.0.113.58", BURST);

        assertThat(attempt("203.0.113.58").getStatus()).isEqualTo(429);

        assertThat(output.getAll()).contains("RATE_LIMITED").doesNotContain("203.0.113.58");
    }
}
