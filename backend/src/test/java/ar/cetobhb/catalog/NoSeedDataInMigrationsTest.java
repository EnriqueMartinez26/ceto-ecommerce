package ar.cetobhb.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

/**
 * CLAUDE.md section 6 / rule 18: migrations never load data (INSERT, COPY, MERGE); fixtures live in tests.
 * Comments are stripped first so prose like "-- copy of the table" is not a false positive. Java-based
 * migrations are rejected outright because their content cannot be inspected statically, including those that
 * reach {@code JavaMigration} through abstract base classes or intermediate interfaces. This is a static
 * guard on the migration sources (a text scan, it does not parse SQL), not a runtime proof that no row exists
 * after migrating.
 *
 * <p>KNOWN LIMITS (accepted, this guard is final): string literals are not stripped, so {@code 'copy of'}
 * inside a literal can false-positive, and {@code SELECT '--'; INSERT ...} on one line can hide an INSERT
 * because the {@code --} inside the literal is treated as a comment start. The scan scope is pinned to
 * {@code classpath:db/migration} by {@link #flywayLocationsResolveOnlyToTheScannedDirectory()}.
 */
class NoSeedDataInMigrationsTest {

    private static final String JAVA_MIGRATION = "org.flywaydb.core.api.migration.JavaMigration";
    private static final String DEFAULT_LOCATION = "classpath:db/migration";
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern DATA_STATEMENT = Pattern.compile(
            "\\b(?:insert\\s+into|merge\\s+into)\\b|\\bcopy(?:\\s+[\\w\"]|\\s*\\()",
            Pattern.CASE_INSENSITIVE);

    @TempDir
    Path tempDir;

    @Test
    void migrationsContainNoDataStatements() throws IOException {
        ClassLoader classLoader = NoSeedDataInMigrationsTest.class.getClassLoader();
        assertThat(migrationResources(classLoader, "sql")).as("SQL migrations on the classpath").isNotEmpty();
        assertThat(seedDataMigrations(classLoader)).as("migrations with INSERT/COPY/MERGE statements").isEmpty();
    }

    @Test
    void flywayLocationsResolveOnlyToTheScannedDirectory() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new PathMatchingResourcePatternResolver().getResource("classpath:application.yml"));
        Properties config = yaml.getObject();
        assertThat(config).isNotNull();
        String locations = config.getProperty("spring.flyway.locations");
        assertThat(locations == null ? DEFAULT_LOCATION : locations.trim())
                .as("effective spring.flyway.locations (Flyway default when unset)")
                .isEqualTo(DEFAULT_LOCATION);
        assertThat(config.stringPropertyNames()).as("no indexed flyway locations")
                .noneMatch(name -> name.startsWith("spring.flyway.locations["));
    }

    @Test
    void migrationsAreNotJavaBased() throws IOException {
        ClassLoader classLoader = NoSeedDataInMigrationsTest.class.getClassLoader();
        List<String> javaMigrations = new ArrayList<>();
        javaMigrations.addAll(descriptions(migrationResources(classLoader, "java")));
        javaMigrations.addAll(descriptions(migrationResources(classLoader, "class")));
        assertThat(javaMigrations).as("Java-based migrations cannot be inspected for seed data").isEmpty();
        assertThat(javaMigrationClassesOnClasspath()).as("classes implementing JavaMigration").isEmpty();
    }

    @Test
    void scansEveryClasspathRootThatHasMigrations() throws IOException {
        Path cleanRoot = Files.createDirectories(tempDir.resolve("root1/db/migration"));
        Path seedRoot = Files.createDirectories(tempDir.resolve("root2/db/migration"));
        Files.writeString(cleanRoot.resolve("V1__schema.sql"), "CREATE TABLE products (id bigint);");
        Files.writeString(seedRoot.resolve("V2__seed.sql"), "INSERT INTO products VALUES (1);");
        URL[] roots = {tempDir.resolve("root1").toUri().toURL(), tempDir.resolve("root2").toUri().toURL()};
        try (URLClassLoader classLoader = new URLClassLoader(roots, null)) {
            assertThat(seedDataMigrations(classLoader)).singleElement().asString().contains("V2__seed.sql");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "archfixtures.ar.cetobhb.javamigration.DirectMigration",
            "archfixtures.ar.cetobhb.javamigration.IndirectMigration",
            "archfixtures.ar.cetobhb.javamigration.ViaInterfaceMigration"})
    void detectsJavaMigrationsThroughBaseClassesAndInterfaces(String className) throws IOException {
        assertThat(isJavaMigration(className, readers())).as(className).isTrue();
    }

    @Test
    void doesNotFlagClassesThatAreNotMigrations() throws IOException {
        assertThat(isJavaMigration("archfixtures.ar.cetobhb.javamigration.NotAMigration", readers())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT\n  INTO products (name) VALUES ('x');",
            "insert  into products (name) values ('x');",
            "  InSeRt\tINTO products VALUES (1);",
            "COPY products FROM '/tmp/data.csv';",
            "COPY  products FROM '/tmp/data.csv';",
            "COPY\tproducts FROM '/tmp/data.csv';",
            "COPY\r\nproducts FROM '/tmp/data.csv';",
            "COPY (SELECT 1) TO '/tmp/out.csv';",
            "COPY(SELECT 1) TO '/tmp/out.csv';",
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

    /** Every {@code db/migration} directory on the classpath is scanned, not only the first one found. */
    private static List<Resource> migrationResources(ClassLoader classLoader, String extension) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
        return Arrays.asList(resolver.getResources("classpath*:db/migration/**/*." + extension));
    }

    private static List<String> descriptions(List<Resource> resources) {
        return resources.stream().map(Resource::getDescription).toList();
    }

    private static List<String> seedDataMigrations(ClassLoader classLoader) throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Resource migration : migrationResources(classLoader, "sql")) {
            if (containsDataStatement(migration.getContentAsString(StandardCharsets.UTF_8))) {
                offenders.add(migration.getDescription());
            }
        }
        return offenders;
    }

    private static MetadataReaderFactory readers() {
        return new SimpleMetadataReaderFactory(NoSeedDataInMigrationsTest.class.getClassLoader());
    }

    private static List<String> javaMigrationClassesOnClasspath() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new SimpleMetadataReaderFactory(resolver);
        List<String> offenders = new ArrayList<>();
        for (String pattern : List.of("classpath*:db/migration/**/*.class", "classpath*:ar/cetobhb/**/*.class")) {
            for (Resource resource : resolver.getResources(pattern)) {
                ClassMetadata metadata = readers.getMetadataReader(resource).getClassMetadata();
                if (isJavaMigration(metadata.getClassName(), readers)) {
                    offenders.add(metadata.getClassName());
                }
            }
        }
        return offenders;
    }

    /** True when the class reaches {@code JavaMigration} through any chain of superclasses or interfaces. */
    static boolean isJavaMigration(String className, MetadataReaderFactory readers) throws IOException {
        return reachesJavaMigration(className, readers, new HashSet<>());
    }

    private static boolean reachesJavaMigration(String className, MetadataReaderFactory readers, Set<String> visited)
            throws IOException {
        if (JAVA_MIGRATION.equals(className)) {
            return true;
        }
        if (className == null || className.startsWith("java.") || !visited.add(className)) {
            return false;
        }
        ClassMetadata metadata = readers.getMetadataReader(className).getClassMetadata();
        for (String interfaceName : metadata.getInterfaceNames()) {
            if (reachesJavaMigration(interfaceName, readers, visited)) {
                return true;
            }
        }
        return reachesJavaMigration(metadata.getSuperClassName(), readers, visited);
    }
}
