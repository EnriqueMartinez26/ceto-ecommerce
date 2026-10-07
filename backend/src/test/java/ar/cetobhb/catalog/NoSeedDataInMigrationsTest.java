package ar.cetobhb.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

/**
 * CLAUDE.md section 6 / rule 18: migrations never load data (INSERT, COPY, MERGE); fixtures live in tests.
 * Comments are stripped first so prose like "-- "\b(insert\s+into|copy\s|merge\s+into)\b"of the table" is not a false positive. Java-based
 * migrations are rejected outright because their content cannot be inspected statically. This is a static
 * guard on the migration sources, not a runtime proof that no row exists after migrating.
 */
class NoSeedDataInMigrationsTest {

    private static final String MIGRATIONS_LOCATION = "db/migration";
    private static final String JAVA_MIGRATION = "org.flywaydb.core.api.migration.JavaMigration";
    private static final String BASE_JAVA_MIGRATION = "org.flywaydb.core.api.migration.BaseJavaMigration";
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern DATA_STATEMENT = Pattern.compile(
            "\\b(insert\\s+into|copy\\s|merge\\s+into)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void migrationsContainNoDataStatements() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.list(migrationsDirectory())) {
            offenders = files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(NoSeedDataInMigrationsTest::containsDataStatement)
                    .map(p -> p.getFileName().toString())
                    .toList();
        }
        assertThat(offenders).as("migrations with INSERT/COPY/MERGE statements").isEmpty();
    }

    @Test
    void migrationsAreNotJavaBased() throws IOException {
        List<String> javaMigrations;
        try (Stream<Path> files = Files.walk(migrationsDirectory())) {
            javaMigrations = files.filter(p -> p.getFileName().toString().endsWith(".java")
                            || p.getFileName().toString().endsWith(".class"))
                    .map(p -> p.getFileName().toString())
                    .toList();
        }
        assertThat(javaMigrations).as("Java-based migrations cannot be inspected for seed data").isEmpty();
        assertThat(javaMigrationClassesOnClasspath()).as("classes implementing JavaMigration").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT\n  INTO products (name) VALUES ('x');",
            "insert  into products (name) values ('x');",
            "  InSeRt\tINTO products VALUES (1);",
            "COPY products FROM '/tmp/data.csv';",
            "MERGE INTO products p USING src s ON p.id = s.id WHEN MATCHED THEN DELETE;",
            "CREATE TABLE t (id int);\n/* ok */ INSERT INTO t VALUES (1);"})
    void detectsDataStatementsRegardlessOfCaseAndWhitespace(String sql) {
        assertThat(containsDataStatement(sql)).as(sql).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-- copy of the table\nCREATE TABLE products (id bigint);",
            "/* insert into products is forbidden here */\nCREATE TABLE products (id bigint);",
            "CREATE TABLE products (id bigint); -- merge into nothing",
            "CREATE TABLE copy_log (id bigint);",
            "CREATE TABLE products (insertion_point int);"})
    void ignoresCommentsAndLookalikeIdentifiers(String sql) {
        assertThat(containsDataStatement(sql)).as(sql).isFalse();
    }

    static boolean containsDataStatement(String sql) {
        return DATA_STATEMENT.matcher(stripComments(sql)).find();
    }

    private static String stripComments(String sql) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ");
    }

    private static boolean containsDataStatement(Path file) {
        try {
            return containsDataStatement(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file.getFileName(), e);
        }
    }

    private static Path migrationsDirectory() {
        URL resource = NoSeedDataInMigrationsTest.class.getClassLoader().getResource(MIGRATIONS_LOCATION);
        assertThat(resource).as("classpath resource " + MIGRATIONS_LOCATION).isNotNull();
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("bad migrations location " + resource, e);
        }
    }

    private static List<String> javaMigrationClassesOnClasspath() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        SimpleMetadataReaderFactory readers = new SimpleMetadataReaderFactory(resolver);
        List<String> offenders = new ArrayList<>();
        for (String pattern : List.of("classpath*:db/migration/**/*.class", "classpath*:ar/cetobhb/**/*.class")) {
            for (Resource resource : resolver.getResources(pattern)) {
                ClassMetadata metadata = readers.getMetadataReader(resource).getClassMetadata();
                if (Arrays.asList(metadata.getInterfaceNames()).contains(JAVA_MIGRATION)
                        || BASE_JAVA_MIGRATION.equals(metadata.getSuperClassName())) {
                    offenders.add(metadata.getClassName());
                }
            }
        }
        return offenders;
    }
}
