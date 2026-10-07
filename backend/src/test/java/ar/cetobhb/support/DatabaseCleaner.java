package ar.cetobhb.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test helper: empties every application table (never Flyway history) and restarts identity columns. */
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
    }
}
