package ar.cetobhb.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Test helper for deterministic hold-and-probe lock tests. It proves that a probe is really BLOCKED by Postgres (not
 * merely slow): it polls {@code pg_stat_activity} / {@code pg_locks} on its own autocommit connection (so it is not
 * affected by the transaction snapshot of the holder) until some other backend of this database is waiting on a lock
 * ({@code wait_event_type = 'Lock'} with an ungranted lock in {@code pg_locks}), or the deadline passes.
 */
public final class LockProbe {

    private static final String WAITING = """
            SELECT a.pid, a.query
              FROM pg_stat_activity a
             WHERE a.datname = current_database()
               AND a.pid <> pg_backend_pid()
               AND a.wait_event_type = 'Lock'
               AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = a.pid AND NOT l.granted)""";

    private LockProbe() {
    }

    /**
     * Waits until a backend is waiting on a lock and its current statement contains {@code queryFragment} (case
     * insensitive). Fails with a message listing what was seen when the deadline passes.
     */
    public static void awaitBlocked(DataSource dataSource, Duration deadline, String queryFragment) {
        long end = System.nanoTime() + deadline.toNanos();
        List<String> seen = new ArrayList<>();
        do {
            seen = waitingQueries(dataSource);
            for (String query : seen) {
                if (query.toLowerCase().contains(queryFragment.toLowerCase())) {
                    return;
                }
            }
            sleep();
        } while (System.nanoTime() < end);
        throw new AssertionError("no backend was seen waiting on a lock (wait_event_type = 'Lock') with a statement"
                + " containing '" + queryFragment + "' within " + deadline + "; waiting statements seen: " + seen);
    }

    /** Statements of the backends currently waiting on a lock. */
    public static List<String> waitingQueries(DataSource dataSource) {
        List<String> queries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(WAITING);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                queries.add(rows.getString("query"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read pg_stat_activity", e);
        }
        return queries;
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the probe to block", e);
        }
    }
}
