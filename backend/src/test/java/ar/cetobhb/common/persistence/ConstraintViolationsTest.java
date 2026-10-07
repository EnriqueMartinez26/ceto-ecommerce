package ar.cetobhb.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;

class ConstraintViolationsTest {

    private static DataIntegrityViolationException wrapping(String constraintName) {
        SQLException sql = new SQLException("duplicate key", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, constraintName));
    }

    @Test
    void matchesTheNamedConstraintWalkingTheCauseChainIgnoringCase() {
        assertThat(ConstraintViolations.isConstraint(wrapping("orders_idempotency_key_uk"),
                "ORDERS_IDEMPOTENCY_KEY_UK")).isTrue();
    }

    @Test
    void doesNotMatchAnotherConstraint() {
        assertThat(ConstraintViolations.isConstraint(wrapping("orders_pkey"), "orders_idempotency_key_uk")).isFalse();
    }

    @Test
    void doesNotMatchWhenThereIsNoConstraintViolationInTheChain() {
        assertThat(ConstraintViolations.isConstraint(new IllegalStateException("x", new SQLException("y", "23505")),
                "orders_idempotency_key_uk")).isFalse();
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void terminatesOnACauseCycleLongerThanOne() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);

        assertThat(ConstraintViolations.isConstraint(a, "orders_idempotency_key_uk")).isFalse();
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void stillFindsTheConstraintBeforeACauseCycle() {
        SQLException sql = new SQLException("duplicate key", "23505");
        ConstraintViolationException violation =
                new ConstraintViolationException("could not execute statement", sql, "orders_idempotency_key_uk");
        RuntimeException a = new RuntimeException("a", violation);
        RuntimeException b = new RuntimeException("b", a);

        assertThat(ConstraintViolations.isConstraint(b, "orders_idempotency_key_uk")).isTrue();
    }

    @Test
    void doesNotMatchAUnnamedConstraintViolation() {
        assertThat(ConstraintViolations.isConstraint(wrapping(null), "orders_idempotency_key_uk")).isFalse();
    }
}
