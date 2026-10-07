package ar.cetobhb.common.persistence;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;

/** Recognises database constraint violations by constraint name, walking the whole cause chain. */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * True when some cause is a Hibernate constraint violation on {@code constraintName} (case-insensitive). The walk
     * keeps the causes it already visited (by identity), so a cause cycle of any length ends the walk instead of
     * looping forever.
     */
    public static boolean isConstraint(Throwable error, String constraintName) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = error;
        while (cause != null && visited.add(cause)) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
