package ar.cetobhb.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helper: empties every application table (never Flyway history), restarts identity columns and restarts
 * standalone sequences such as order_number_seq.
 */
public final class DatabaseCleaner {

    private static final String AUDIT_TABLE = "admin_audit_log";
    private static final String AUDIT_TRIGGER = "admin_audit_log_append_only";

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "SELECT quote_ident(tablename) FROM pg_tables "
                        + "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        if (!tables.isEmpty()) {
            // The append-only audit table raises on TRUNCATE by design; the cleaner (test code only) lifts that
            // guard for its own statement and always puts it back.
            boolean audit = tables.contains(AUDIT_TABLE) && jdbc.queryForObject(
                    "SELECT count(*) FROM pg_trigger WHERE tgname = ? AND NOT tgisinternal",
                    Integer.class, AUDIT_TRIGGER) > 0;
            if (audit) {
                jdbc.execute("ALTER TABLE " + AUDIT_TABLE + " DISABLE TRIGGER " + AUDIT_TRIGGER);
            }
            try {
                jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
            } finally {
                if (audit) {
                    jdbc.execute("ALTER TABLE " + AUDIT_TABLE + " ENABLE TRIGGER " + AUDIT_TRIGGER);
                }
            }
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
