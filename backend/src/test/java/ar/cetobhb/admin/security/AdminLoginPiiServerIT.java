package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * No credential, hash, session id or CSRF token reaches the log or a response body through the login flows
 * (REQ-AUTH-12): a successful login, a failed login with a sentinel username and password, a wrong password for the
 * real account, and logout. At the levels the application ships with, NOTHING about the flows may be in the output
 * (the real account name included), nor with the web and application loggers on DEBUG. The DEBUG output of Spring
 * Security itself is a known source of the account name, the client address and the session id (see the
 * characterization test); the application ships that category at WARN (slice X6.4), which also holds when the root
 * logger is raised to DEBUG. The login limiter's 429 is covered too: its DEBUG line names neither the address nor the
 * typed account.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminLoginPiiServerIT extends AbstractAdminServerIT {

    /**
     * A web-layer DEBUG line that is always printed for the session bootstrap. It is not the request line of
     * DispatcherServlet: that category ships at INFO (the order search text travels in the query string), see
     * AdminOrdersPiiServerIT.
     */
    private static final String WEB_DEBUG_CONTROL = "Mapped to ar.cetobhb.admin.controller.AdminSessionController";
    private static final String SENTINEL_USER = "SentinelLoginName-55120";
    private static final String SENTINEL_PASSWORD = "SentinelLoginSecret-55120";
    private static final String[] CATEGORIES = {"org.springframework.security", "org.springframework.web", "ar.cetobhb",
        LoggingSystem.ROOT_LOGGER_NAME};

    @Autowired
    private LoggingSystem loggingSystem;

    private final List<LogLevel> configured = new ArrayList<>();

    @BeforeEach
    void rememberLevels() {
        configured.clear();
        for (String category : CATEGORIES) {
            configured.add(loggingSystem.getLoggerConfiguration(category).getConfiguredLevel());
        }
    }

    @AfterEach
    void restoreLevels() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            loggingSystem.setLogLevel(CATEGORIES[i], configured.get(i));
        }
    }

    /** What the flows produced besides the log: every response body and header block, and every secret that existed. */
    private record Run(List<String> responses, List<String> secrets) {
    }

    private Run runTheFlows() throws Exception {
        List<String> responses = new ArrayList<>();
        List<String> secrets = new ArrayList<>(List.of(SENTINEL_USER, SENTINEL_PASSWORD, AdminTestCredentials.password(),
                AdminTestCredentials.passwordHash(), AdminTestCredentials.passwordHash().substring("{bcrypt}".length())));

        AdminClient failing = newClient();
        failing.bootstrap();
        secrets.add(failing.xsrf());
        responses.add(show(failing.login(SENTINEL_USER, SENTINEL_PASSWORD)));
        responses.add(show(failing.login(AdminTestCredentials.username(), SENTINEL_PASSWORD)));

        AdminClient client = newClient();
        client.bootstrap();
        secrets.add(client.xsrf());
        AdminClient.Reply login = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());
        responses.add(show(login));
        secrets.add(client.cookie(AdminClient.SESSION_COOKIE));
        client.bootstrap();
        secrets.add(client.xsrf());
        AdminClient.Reply logout = client.logout();
        responses.add(show(logout));

        assertThat(login.status()).isEqualTo(204);
        assertThat(logout.status()).isEqualTo(204);
        return new Run(responses, secrets.stream().filter(s -> s != null && !s.isBlank()).toList());
    }

    private static String show(AdminClient.Reply reply) {
        return reply.status() + " " + reply.body() + " " + reply.headers().entrySet().stream()
                .filter(entry -> !entry.getKey().equals("set-cookie")).toList();
    }

    /** The shipped configuration: Spring Security's own category is at WARN (X6.4), not left to the root logger. */
    @Test
    void springSecurityShipsAtWarnSoItsDebugLinesAreNeverPrintedByDefault() {
        assertThat(loggingSystem.getLoggerConfiguration("org.springframework.security").getConfiguredLevel())
                .as("configured, not inherited").isEqualTo(LogLevel.WARN);
        assertThat(loggingSystem.getLoggerConfiguration("org.springframework.security").getEffectiveLevel())
                .isEqualTo(LogLevel.WARN);
        assertThat(loggingSystem.getLoggerConfiguration("org.springframework.security.web.authentication")
                .getEffectiveLevel()).as("sub-categories inherit it").isEqualTo(LogLevel.WARN);
    }

    /**
     * Raising the root logger to DEBUG (the usual way to "see what happens") must not bring the account name, the client
     * address or the session id back: the explicit WARN of Spring Security's category wins over the root level.
     */
    @Test
    void withTheRootLoggerOnDebugSpringSecurityStillPrintsNeitherTheAccountNorTheAddressNorTheSessionId(
            CapturedOutput output) throws Exception {
        loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);

        Run run = runTheFlows();

        assertThat(output.getAll()).as("positive control: the root DEBUG lines are in the capture")
                .contains(WEB_DEBUG_CONTROL);
        assertThat(output.getAll()).doesNotContain("RemoteIpAddress=").doesNotContain("Set SecurityContextHolder")
                .doesNotContain(AdminTestCredentials.username());
        for (String secret : run.secrets()) {
            assertThat(output.getAll()).as("the root DEBUG output must not contain a secret (starts %s)", head(secret))
                    .doesNotContain(secret);
        }
    }

    /**
     * The 429 of the login limiter: its DEBUG line (positive control) carries neither the client address nor the typed
     * account or password, and neither does the response.
     */
    @Test
    void aLimitedLoginLeavesNoAddressAccountOrSecretInTheOutputOrTheBody(CapturedOutput output) throws Exception {
        loggingSystem.setLogLevel("ar.cetobhb", LogLevel.DEBUG);
        loggingSystem.setLogLevel("org.springframework.web", LogLevel.DEBUG);
        AdminClient attacker = newClient();
        attacker.bootstrap();
        List<AdminClient.Reply> replies = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            replies.add(attacker.login(SENTINEL_USER, SENTINEL_PASSWORD));
        }

        assertThat(replies).extracting(AdminClient.Reply::status).contains(401, 429);
        assertThat(output.getAll()).as("positive control: the limiter's DEBUG line is captured")
                .contains("rejected by the rate limit");
        List<String> secrets = List.of(attacker.forwardedFor(), SENTINEL_USER, SENTINEL_PASSWORD,
                AdminTestCredentials.username(), AdminTestCredentials.password(), AdminTestCredentials.passwordHash(),
                attacker.xsrf());
        for (String secret : secrets) {
            assertThat(output.getAll()).as("the output must not contain (starts %s)", head(secret)).doesNotContain(secret);
            replies.forEach(reply -> assertThat(show(reply)).as("a response must not contain (starts %s)", head(secret))
                    .doesNotContain(secret));
        }
    }

    @Test
    void atTheShippedLevelsTheFlowsLeaveNeitherSecretsNorTheAccountNameInTheOutputOrTheBodies(CapturedOutput output)
            throws Exception {
        Run run = runTheFlows();

        for (String secret : run.secrets()) {
            assertThat(output.getAll()).as("the output must not contain a secret (starts %s)", head(secret))
                    .doesNotContain(secret);
            run.responses().forEach(response -> assertThat(response).as("a response must not contain a secret")
                    .doesNotContain(secret));
        }
        assertThat(output.getAll()).doesNotContain(AdminTestCredentials.username());
        run.responses().forEach(response -> assertThat(response).doesNotContain(AdminTestCredentials.username()));
    }

    @Test
    void withTheApplicationAndWebLoggersOnDebugTheOutputStillHoldsNoSecretOrAccountName(CapturedOutput output)
            throws Exception {
        loggingSystem.setLogLevel("org.springframework.web", LogLevel.DEBUG);
        loggingSystem.setLogLevel("ar.cetobhb", LogLevel.DEBUG);

        Run run = runTheFlows();

        assertThat(output.getAll()).as("positive control: the web DEBUG lines are in the capture")
                .contains(WEB_DEBUG_CONTROL);
        for (String secret : run.secrets()) {
            assertThat(output.getAll()).as("the DEBUG output must not contain a secret (starts %s)", head(secret))
                    .doesNotContain(secret);
        }
        assertThat(output.getAll()).doesNotContain(AdminTestCredentials.username());
    }

    /**
     * A session that was superseded by a newer login and one that expired from idleness both end with a 401; neither
     * may print a session id or a token, at the shipped levels and with the web and application loggers on DEBUG.
     */
    @Test
    void aSupersededAndAnExpiredSessionLeaveNoSessionIdOrTokenInTheOutputOrTheBodies(CapturedOutput output)
            throws Exception {
        loggingSystem.setLogLevel("org.springframework.web", LogLevel.DEBUG);
        loggingSystem.setLogLevel("ar.cetobhb", LogLevel.DEBUG);
        List<String> secrets = new ArrayList<>(List.of(AdminTestCredentials.password(),
                AdminTestCredentials.passwordHash()));
        List<String> responses = new ArrayList<>();

        AdminClient first = loggedInClient();
        secrets.add(first.cookie(AdminClient.SESSION_COOKIE));
        secrets.add(first.xsrf());
        AdminClient second = loggedInClient();
        String secondSession = second.cookie(AdminClient.SESSION_COOKIE);
        secrets.add(secondSession);
        secrets.add(second.xsrf());

        AdminClient.Reply superseded = first.get(AdminClient.PROBE_PATH);
        responses.add(show(superseded));
        findServerSession(secondSession).setMaxInactiveInterval(1);
        Thread.sleep(1_600);
        AdminClient.Reply expired = second.get(AdminClient.PROBE_PATH);
        responses.add(show(expired));

        assertThat(superseded.status()).isEqualTo(401);
        assertThat(expired.status()).isEqualTo(401);
        assertThat(output.getAll()).as("positive control: the DEBUG lines of the web layer are captured")
                .contains(WEB_DEBUG_CONTROL);
        for (String secret : secrets) {
            assertThat(output.getAll()).as("the output must not contain a secret (starts %s)", head(secret))
                    .doesNotContain(secret);
            responses.forEach(response -> assertThat(response).doesNotContain(secret));
        }
        assertThat(output.getAll()).doesNotContain(AdminTestCredentials.username());
    }

    /**
     * Characterization, and the reason slice X6.4 ships {@code org.springframework.security} at WARN: with the Security
     * loggers on DEBUG the output names the authenticated account ("Set SecurityContextHolder to ... Username=..."),
     * the client address ("RemoteIpAddress=...") and the session id ("Invalidated session ..."). Nothing prints them at the shipped levels
     * (the first test). If Security stops printing them, delete this test.
     */
    @Test
    void positiveControlSecurityOnDebugPrintsTheAccountNameAndTheSessionIdSoTheShippedLevelIsLoadBearing(
            CapturedOutput output) throws Exception {
        loggingSystem.setLogLevel("org.springframework.security", LogLevel.DEBUG);

        Run run = runTheFlows();

        assertThat(output.getAll()).contains(AdminTestCredentials.username()).contains("RemoteIpAddress=");
        assertThat(run.secrets()).anySatisfy(secret -> assertThat(output.getAll()).contains(secret));
        // The password, the hash and the typed sentinels stay out even then.
        assertThat(output.getAll()).doesNotContain(AdminTestCredentials.password())
                .doesNotContain(AdminTestCredentials.passwordHash()).doesNotContain(SENTINEL_USER)
                .doesNotContain(SENTINEL_PASSWORD);
    }

    private static String head(String secret) {
        return secret.substring(0, Math.min(6, secret.length())) + "...";
    }
}
