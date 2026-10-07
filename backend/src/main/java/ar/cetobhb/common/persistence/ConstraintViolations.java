package ar.cetobhb.common.persistence;

import org.hibernate.exception.ConstraintViolationException;

/** Recognises database constraint violations by constraint name, walking the whole cause chain. */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /** True when some cause is a Hibernate constraint violation on {@code constraintName} (case-insensitive). */
    public static boolean isConstraint(Throwable error, String constraintName) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }
}
