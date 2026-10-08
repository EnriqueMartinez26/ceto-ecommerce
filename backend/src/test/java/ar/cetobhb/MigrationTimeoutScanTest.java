package ar.cetobhb;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.MigrationTimeoutScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * A plain {@code SET lock_timeout} in a Flyway migration changes the SESSION of a pooled connection and leaks into
 * the requests that reuse it (the pool's own lock_timeout is the application's safety net, CLAUDE.md rule 3).
 * Migrations must use {@code SET LOCAL}, which ends with the migration's transaction.
 */
class MigrationTimeoutScanTest {

    @Test
    void noMigrationChangesATimeoutForTheWholeSession() throws IOException {
        List<Resource> migrations = List.of(
                new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql"));
        assertThat(migrations).as("migrations found").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Resource migration : migrations) {
            String sql = new String(migration.getContentAsByteArray(), StandardCharsets.UTF_8);
            violations.addAll(MigrationTimeoutScanner.scan(String.valueOf(migration.getFilename()), sql));
        }
        assertThat(violations).as("session-wide timeout changes").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SET lock_timeout = '5s';",
            "set lock_timeout to 0;",
            "SET   statement_timeout = 0;",
            "SET SESSION lock_timeout = '1s';",
            "SET idle_in_transaction_session_timeout = 0;",
            "SELECT set_config('lock_timeout', '0', false);",
            "ALTER TABLE a ADD b int;\nSET\nlock_timeout = '2s';\nALTER TABLE c ADD d int;"})
    void flagsASessionWideTimeoutChange(String sql) {
        assertThat(MigrationTimeoutScanner.scan("V9__x.sql", sql)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SET LOCAL lock_timeout = '5s';",
            "set local statement_timeout = 0;",
            "SELECT set_config('lock_timeout', '5s', true);",
            "-- SET lock_timeout = '5s'; (a comment)",
            "/* SET statement_timeout = 0; */ SELECT 1;",
            "CREATE TABLE lock_timeout_log (id int);",
            "SET search_path = public;"})
    void acceptsTheLocalFormAndIgnoresCommentsAndOtherSettings(String sql) {
        assertThat(MigrationTimeoutScanner.scan("V9__x.sql", sql)).isEmpty();
    }
}
