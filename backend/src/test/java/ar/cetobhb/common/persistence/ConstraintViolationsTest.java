package ar.cetobhb.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
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
    void doesNotMatchAUnnamedConstraintViolation() {
        assertThat(ConstraintViolations.isConstraint(wrapping(null), "orders_idempotency_key_uk")).isFalse();
    }
}
