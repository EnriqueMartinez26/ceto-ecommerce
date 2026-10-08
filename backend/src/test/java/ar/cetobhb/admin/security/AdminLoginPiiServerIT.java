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
 * Security itself is a known source of the account name and the session id (see the characterization test); keeping
 * that category at WARN is slice X6.4.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminLoginPiiServerIT extends AbstractAdminServerIT {

    private static final String SENTINEL_USER = "SentinelLoginName-55120";
    private static final String SENTINEL_PASSWORD = "SentinelLoginSecret-55120";
    private static final String[] CATEGORIES = {"org.springframework.security", "org.springframework.web", "ar.cetobhb"};

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
                .contains("/api/admin/session");
        for (String secret : run.secrets()) {
            assertThat(output.getAll()).as("the DEBUG output must not contain a secret (starts %s)", head(secret))
                    .doesNotContain(secret);
        }
        assertThat(output.getAll()).doesNotContain(AdminTestCredentials.username());
    }

    /**
     * Characterization, and the reason slice X6.4 sets {@code org.springframework.security} to WARN: with the Security
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
