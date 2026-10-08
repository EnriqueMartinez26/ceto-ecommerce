package ar.cetobhb.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helper for "no personal data in the audit table": looks for a sentinel in EVERY text-like column of
 * {@code admin_audit_log}, found through the catalog instead of a hard-coded column list, so a column added later is
 * scanned too.
 */
public final class AuditLogScan {

    private static final String TABLE = "admin_audit_log";

    private AuditLogScan() {
    }

    /** Names of the columns that hold at least one value containing the sentinel (case-insensitive). */
    public static List<String> columnsContaining(JdbcTemplate jdbc, String sentinel) {
        List<String> columns = jdbc.queryForList(
                "SELECT quote_ident(column_name) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?", String.class, TABLE);
        List<String> hits = new ArrayList<>();
        for (String column : columns) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE " + column
                    + "::text ILIKE '%' || ? || '%'", Integer.class, sentinel);
            if (count != null && count > 0) {
                hits.add(column);
            }
        }
        return hits;
    }

    /** Number of text-like columns the scan covers (a guard against a scan that silently covers nothing). */
    public static int scannedColumns(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ?", Integer.class, TABLE);
    }
}
