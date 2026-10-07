package ar.cetobhb.common.web;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Personal-data-free summary of a {@link Throwable} for log lines. It emits class names, the SQLState, the violated
 * constraint name, a few stack frames of the root cause and the first application frames of the top-level exception
 * (the call site), and never any message: Postgres CHECK violations carry a
 * whole row in their message and Jackson messages echo the rejected value (CLAUDE.md section 5).
 */
public final class SafeErrors {

    private static final int ROOT_FRAMES = 8;
    private static final int APP_FRAMES = 3;
    private static final String APP_PACKAGE = "ar.cetobhb.";

    private SafeErrors() {
    }

    public static String describe(Throwable error) {
        if (error == null) {
            return "null";
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        StringBuilder out = new StringBuilder();
        Throwable cause = error;
        Throwable root = error;
        while (cause != null && visited.add(cause)) {
            if (!out.isEmpty()) {
                out.append(" <- ");
            }
            out.append(cause.getClass().getName());
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                out.append("[sqlState=").append(sql.getSQLState()).append(']');
            }
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                out.append("[constraint=").append(violation.getConstraintName()).append(']');
            }
            root = cause;
            cause = cause.getCause();
        }
        if (root != error) {
            int shown = 0;
            for (StackTraceElement frame : error.getStackTrace()) {
                if (shown == APP_FRAMES) {
                    break;
                }
                if (frame.getClassName().startsWith(APP_PACKAGE)) {
                    out.append("\n\tcall site at ").append(frame);
                    shown++;
                }
            }
        }
        StackTraceElement[] frames = root.getStackTrace();
        for (int i = 0; i < Math.min(ROOT_FRAMES, frames.length); i++) {
            out.append("\n\tat ").append(frames[i]);
        }
        return out.toString();
    }
}
