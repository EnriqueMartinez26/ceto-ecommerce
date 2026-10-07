package ar.cetobhb.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helper: empties every application table (never Flyway history), restarts identity columns and restarts
 * standalone sequences such as order_number_seq.
 */
public final class DatabaseCleaner {

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "SELECT quote_ident(tablename) FROM pg_tables "
                        + "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
        // Identity and owned sequences are already restarted above; only standalone ones (order_number_seq) remain.
        List<String> sequences = jdbc.queryForList(
                "SELECT quote_ident(s.sequencename) FROM pg_sequences s WHERE s.schemaname = 'public' "
                        + "AND NOT EXISTS (SELECT 1 FROM pg_depend d JOIN pg_class c ON c.oid = d.objid "
                        + "WHERE d.classid = 'pg_class'::regclass "
                        + "AND c.relname = s.sequencename AND c.relnamespace = 'public'::regnamespace "
                        + "AND d.deptype IN ('a', 'i'))",
                String.class);
        sequences.forEach(sequence -> jdbc.execute("ALTER SEQUENCE " + sequence + " RESTART"));
    }
}
