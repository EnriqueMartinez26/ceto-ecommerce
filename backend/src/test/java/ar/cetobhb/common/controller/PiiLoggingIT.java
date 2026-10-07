package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Personal data must never reach the log (CLAUDE.md section 5). Postgres CHECK violations carry the failing row in
 * their message, Jackson messages echo the rejected value. A sentinel phone number stands in for both.
 */
@ExtendWith(OutputCaptureExtension.class)
class PiiLoggingIT extends AbstractPostgresIT {

    private static final String SENTINEL = "+5491199998888";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private ApiExceptionHandler handler;

    @Autowired
    private Environment environment;

    @BeforeEach
    void createProbeTable() {
        jdbc.execute("DROP TABLE IF EXISTS pii_probe");
        jdbc.execute("CREATE TABLE pii_probe (id int, name text, phone text, CONSTRAINT test_pii_ck CHECK (id > 0))");
    }

    @AfterEach
    void dropProbeTable() {
        jdbc.execute("DROP TABLE IF EXISTS pii_probe");
    }

    private DataIntegrityViolationException forcedCheckViolation() {
        RuntimeException[] failure = new RuntimeException[1];
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                try {
                    entityManager.createNativeQuery("INSERT INTO pii_probe (id, name, phone) VALUES (0, 'SentinelName', '"
                            + SENTINEL + "')").executeUpdate();
                } catch (PersistenceException e) {
                    failure[0] = e;
                    throw e;
                }
            });
        } catch (PersistenceException expected) {
            // What a repository would throw: Spring's translation of the Hibernate constraint violation.
            return new DataIntegrityViolationException(failure[0].getMessage(), failure[0]);
        }
        throw new AssertionError("the CHECK violation was not raised");
    }

    @Test
    void theDatabaseDriverRowDetailNeverReachesTheLog(CapturedOutput output) {
        DataIntegrityViolationException violation = forcedCheckViolation();

        assertThat(violation.getMessage()).contains(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL, "SentinelName", "Failing row");
    }

    @Test
    void handlingAnIntegrityViolationLogsClassChainAndConstraintOnly(CapturedOutput output) {
        DataIntegrityViolationException violation = forcedCheckViolation();

        handler.handleConflict(violation, new MockHttpServletRequest("POST", "/x"));

        assertThat(output.getAll()).contains("CONFLICT").contains("constraint=test_pii_ck")
                .doesNotContain(SENTINEL, "SentinelName", "Failing row");
    }

    @Test
    void anUnexpectedFailureLogsAFullSafeSummaryWithoutItsMessage(CapturedOutput output) {
        handler.handleUnexpected(new IllegalStateException("boom: " + SENTINEL), new MockHttpServletRequest());

        assertThat(output.getAll()).contains("INTERNAL_ERROR", "java.lang.IllegalStateException")
                .doesNotContain(SENTINEL, "boom");
    }

    @Test
    void aRejectedJsonValueIsNeverLoggedNorReturned(CapturedOutput output) throws Exception {
        JacksonException echo = catchThrowableOfType(() -> json.readValue("{\"quantity\":\"" + SENTINEL + "\"}",
                Quantity.class));
        assertThat(echo.getMessage()).contains(SENTINEL);

        MockHttpServletResponse unused = new MockHttpServletResponse();
        String body = String.valueOf(handler.handleException(new HttpMessageNotReadableException("unreadable", echo, null),
                new ServletWebRequest(new MockHttpServletRequest("POST", "/x"), unused)).getBody());

        assertThat(output.getAll()).doesNotContain(SENTINEL);
        assertThat(body).doesNotContain(SENTINEL);
    }

    @Test
    void theLogPatternCarriesTheRequestId() {
        assertThat(environment.getProperty("logging.pattern.level")).contains("%X{requestId");
    }

    record Quantity(Integer quantity) {
    }

    private JacksonException catchThrowableOfType(ThrowingRunnable runnable) {
        Throwable thrown = assertThatThrownBy(runnable::run).actual();
        assertThat(thrown).isInstanceOf(JacksonException.class);
        return (JacksonException) thrown;
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
