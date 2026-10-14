package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.domain.TokenBucketLimiter;
import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.support.MutableClock;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The login limiter, decided with an injected clock and never by waiting (REQ-AUTH-10): one token per login POST per
 * client address, the same answer for every kind of attempt, a 429 problem with an integer {@code Retry-After}, and a
 * DEBUG line that carries neither the address nor anything the client typed. Only the login processing request counts.
 */
class AdminLoginRateLimitFilterTest {

    private static final String LOGIN = "/api/admin/login";
    private static final String CLIENT_A = "198.51.100.7";
    private static final String CLIENT_B = "198.51.100.8";
    private static final int BURST = 3;
    private static final Duration REFILL = Duration.ofSeconds(90);

    private final MutableClock clock = new MutableClock();
    private final TokenBucketLimiter limiter = new TokenBucketLimiter(clock, BURST, REFILL, 100, 20,
            Duration.ofMinutes(90));
    private final AdminLoginRateLimitFilter filter = new AdminLoginRateLimitFilter(limiter, LOGIN,
            new ProblemResponses(JsonMapper.builder().build()));

    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(AdminLoginRateLimitFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureTheFilterLog() {
        originalLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.DEBUG);
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheFilterLog() {
        filterLogger.detachAppender(appender);
        appender.stop();
        filterLogger.setLevel(originalLevel);
    }

    private record Exchange(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {

        boolean reachedTheChain() {
            return chain.getRequest() != null;
        }
    }

    private Exchange send(String method, String path, String remoteAddr) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Exchange(request, response, chain);
    }

    private void spendTheBurst(String client) throws Exception {
        for (int i = 0; i < BURST; i++) {
            assertThat(send("POST", LOGIN, client).reachedTheChain()).isTrue();
        }
    }

    @Test
    void theFirstAttemptsUpToTheBurstPassUntouched() throws Exception {
        for (int i = 0; i < BURST; i++) {
            Exchange exchange = send("POST", LOGIN, CLIENT_A);

            assertThat(exchange.reachedTheChain()).as("attempt %d", i + 1).isTrue();
            assertThat(exchange.response().getStatus()).isEqualTo(200);
            assertThat(exchange.response().getContentAsString()).isEmpty();
        }
    }

    @Test
    void theAttemptAfterTheBurstIsA429ProblemAndNeverReachesTheChain() throws Exception {
        spendTheBurst(CLIENT_A);

        Exchange limited = send("POST", LOGIN, CLIENT_A);

        assertThat(limited.reachedTheChain()).isFalse();
        assertThat(limited.response().getStatus()).isEqualTo(429);
        assertThat(limited.response().getContentType()).startsWith("application/problem+json");
        assertThat(limited.response().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(limited.response().getContentAsString()).contains("\"code\":\"RATE_LIMITED\"")
                .contains("\"status\":429").contains("\"instance\":\"/api/admin/login\"");
        assertThat(limited.response().getCookies()).isEmpty();
        assertThat(limited.request().getSession(false)).isNull();
    }

    @Test
    void retryAfterIsAnIntegerNumberOfSecondsAtLeastOneAndMatchesTheRefill() throws Exception {
        spendTheBurst(CLIENT_A);

        Exchange limited = send("POST", LOGIN, CLIENT_A);

        String retryAfter = limited.response().getHeader("Retry-After");
        assertThat(retryAfter).matches("[1-9][0-9]*");
        assertThat(Long.parseLong(retryAfter)).isEqualTo(REFILL.toSeconds());
    }

    @Test
    void retryAfterShrinksAsTimePassesAndTheAttemptIsAdmittedOnceItHasElapsed() throws Exception {
        spendTheBurst(CLIENT_A);
        clock.set(MutableClock.START.plusSeconds(60));

        Exchange early = send("POST", LOGIN, CLIENT_A);
        assertThat(early.response().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(early.response().getHeader("Retry-After"))).isEqualTo(30);

        clock.set(MutableClock.START.plusSeconds(90));
        assertThat(send("POST", LOGIN, CLIENT_A).reachedTheChain()).isTrue();
        assertThat(send("POST", LOGIN, CLIENT_A).response().getStatus()).isEqualTo(429);
    }

    @Test
    void aRejectedAttemptCostsNothingAndDoesNotPushTheNextTokenAway() throws Exception {
        spendTheBurst(CLIENT_A);
        for (int i = 0; i < 20; i++) {
            assertThat(send("POST", LOGIN, CLIENT_A).response().getStatus()).isEqualTo(429);
        }

        clock.set(MutableClock.START.plusSeconds(REFILL.toSeconds()));

        assertThat(send("POST", LOGIN, CLIENT_A).reachedTheChain()).isTrue();
    }

    @Test
    void eachClientAddressHasItsOwnBucket() throws Exception {
        spendTheBurst(CLIENT_A);
        assertThat(send("POST", LOGIN, CLIENT_A).response().getStatus()).isEqualTo(429);

        Exchange other = send("POST", LOGIN, CLIENT_B);

        assertThat(other.reachedTheChain()).isTrue();
    }

    @Test
    void everyKindOfLoginPostSpendsATokenWhateverItCarries() throws Exception {
        // No content type, no body, a query string, a form body that is not a login: the filter looks at none of it.
        MockHttpServletRequest bare = new MockHttpServletRequest("POST", LOGIN);
        MockHttpServletRequest withQuery = new MockHttpServletRequest("POST", LOGIN);
        withQuery.setQueryString("x=1");
        MockHttpServletRequest json = new MockHttpServletRequest("POST", LOGIN);
        json.setContentType("application/json");
        json.setContent("{}".getBytes());
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {bare, withQuery, json}) {
            request.setRemoteAddr(CLIENT_A);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        Exchange fourth = send("POST", LOGIN, CLIENT_A);

        assertThat(fourth.response().getStatus()).isEqualTo(429);
    }

    @ParameterizedTest(name = "{0} {1} is not the login")
    @CsvSource({"GET,/api/admin/login", "HEAD,/api/admin/login", "PUT,/api/admin/login", "POST,/api/admin/login/",
        "POST,/API/admin/login", "POST,/api/admin/Login", "POST,/api/admin/logout", "POST,/api/admin/session",
        "POST,/api/admin/login/x", "POST,/api/checkout/orders"})
    void anythingElseIsNeitherCountedNorLimited(String method, String path) throws Exception {
        spendTheBurst(CLIENT_A);

        Exchange other = send(method, path, CLIENT_A);

        assertThat(other.reachedTheChain()).isTrue();
        assertThat(other.response().getStatus()).isEqualTo(200);
    }

    @Test
    void requestsThatAreNotTheLoginLeaveTheBucketFull() throws Exception {
        for (int i = 0; i < 50; i++) {
            send("GET", LOGIN, CLIENT_A);
            send("POST", "/api/admin/logout", CLIENT_A);
        }

        spendTheBurst(CLIENT_A);
        assertThat(send("POST", LOGIN, CLIENT_A).response().getStatus()).isEqualTo(429);
    }

    @Test
    void theLimitIsLoggedOnceAtDebugWithoutTheAddressOrAnythingTheClientSent() throws Exception {
        spendTheBurst(CLIENT_A);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", LOGIN);
        request.setRemoteAddr(CLIENT_A);
        request.setParameter("username", "SentinelUser-77031");
        request.setParameter("password", "SentinelSecret-77031");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getArgumentArray()).isNullOrEmpty();
        assertThat(event.getFormattedMessage()).isNotBlank().doesNotContain(CLIENT_A).doesNotContain("203.0.113.99")
                .doesNotContain("SentinelUser").doesNotContain("SentinelSecret");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void anAdmittedAttemptLogsNothing() throws Exception {
        send("POST", LOGIN, CLIENT_A);

        assertThat(appender.list).isEmpty();
    }
}
