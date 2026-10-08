package ar.cetobhb.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds statements in a Flyway migration that change a timeout for the whole database SESSION instead of the
 * migration's transaction: {@code SET [SESSION] lock_timeout|statement_timeout|idle_in_transaction_session_timeout}
 * and {@code set_config(<timeout>, ..., false)}. {@code SET LOCAL} and {@code set_config(..., true)} are fine.
 * Comments are removed before scanning.
 */
public final class MigrationTimeoutScanner {

    private static final String TIMEOUTS = "(?:lock_timeout|statement_timeout|idle_in_transaction_session_timeout)";
    private static final Pattern SESSION_SET = Pattern.compile(
            "(?is)\\bset\\s+(?:session\\s+)?" + TIMEOUTS + "\\b");
    private static final Pattern SESSION_SET_CONFIG = Pattern.compile(
            "(?is)\\bset_config\\s*\\(\\s*'" + TIMEOUTS + "'\\s*,[^;]*?,\\s*false\\s*\\)");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    private MigrationTimeoutScanner() {
    }

    /** One message per offending statement; empty when the migration is clean. */
    public static List<String> scan(String fileName, String sql) {
        String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ");
        List<String> violations = new ArrayList<>();
        for (Pattern pattern : List.of(SESSION_SET, SESSION_SET_CONFIG)) {
            Matcher matcher = pattern.matcher(code);
            while (matcher.find()) {
                violations.add(fileName + ": session-wide timeout change near \""
                        + matcher.group().replaceAll("\\s+", " ") + "\" (use SET LOCAL)");
            }
        }
        return violations;
    }
}
