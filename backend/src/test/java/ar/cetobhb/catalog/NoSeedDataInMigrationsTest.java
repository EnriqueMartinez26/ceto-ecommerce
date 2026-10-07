package ar.cetobhb.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** CLAUDE.md section 6 / rule 18: migrations never seed products, prices or content; fixtures live in tests. */
class NoSeedDataInMigrationsTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    @Test
    void migrationsContainNoDataStatements() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            offenders = files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(NoSeedDataInMigrationsTest::containsDataStatement)
                    .map(p -> p.getFileName().toString())
                    .toList();
        }
        assertThat(offenders).as("migrations with INSERT/COPY statements").isEmpty();
    }

    private static boolean containsDataStatement(Path file) {
        try {
            String sql = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return sql.contains("insert into") || sql.contains("copy ");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file.getFileName(), e);
        }
    }
}
