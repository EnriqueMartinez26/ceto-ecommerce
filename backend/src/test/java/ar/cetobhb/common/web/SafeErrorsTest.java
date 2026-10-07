package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

class SafeErrorsTest {

    private static final String SENTINEL = "SENTINEL+5491122223333";

    @Test
    void neverEmitsAnyMessageIncludingNestedOnes() {
        SQLException sql = new SQLException("Detail: Failing row contains (" + SENTINEL + ")", "23514");
        RuntimeException top = new RuntimeException("outer " + SENTINEL, new IllegalStateException(SENTINEL, sql));

        String described = SafeErrors.describe(top);

        assertThat(described).doesNotContain(SENTINEL, "Failing row", "outer");
        assertThat(described).contains("java.lang.RuntimeException", "java.lang.IllegalStateException",
                "java.sql.SQLException", "sqlState=23514");
    }

    @Test
    void includesTheConstraintNameOfAHibernateViolation() {
        SQLException sql = new SQLException(SENTINEL, "23514");
        ConstraintViolationException violation = new ConstraintViolationException(SENTINEL, sql, "test_pii_ck");

        String described = SafeErrors.describe(new RuntimeException(violation));

        assertThat(described).contains(" <- ", "constraint=test_pii_ck").doesNotContain(SENTINEL);
    }

    @Test
    void survivesCauseCyclesAndSuppressedEntries() {
        RuntimeException a = new RuntimeException(SENTINEL);
        RuntimeException b = new RuntimeException(SENTINEL, a);
        a.initCause(b);
        a.addSuppressed(new IllegalArgumentException(SENTINEL));

        String described = SafeErrors.describe(a);

        assertThat(described).doesNotContain(SENTINEL);
    }

    @Test
    void limitsTheRootFramesAndKeepsNoMessage() {
        Throwable deep = new RuntimeException(SENTINEL);

        String described = SafeErrors.describe(deep);

        assertThat(described.lines().filter(l -> l.stripLeading().startsWith("at ")).count()).isLessThanOrEqualTo(8);
    }

    @Test
    void handlesNull() {
        assertThat(SafeErrors.describe(null)).isEqualTo("null");
    }
}
