package ar.cetobhb.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Scans config files for settings that would let Hibernate or JPA generate the schema. Every YAML
 * document ({@code ---}) is scanned separately and keys are compared in relaxed form
 * ({@code ddl-auto}, {@code ddl_auto}, {@code ddlAuto}, env-var style), so no document can hide a violation.
 */
public final class SchemaConfigScanner {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            normalize("spring.jpa.hibernate.ddl-auto"),
            normalize("spring.jpa.properties.hibernate.hbm2ddl.auto"),
            normalize("spring.jpa.properties.jakarta.persistence.schema-generation.database.action"),
            normalize("spring.jpa.properties.javax.persistence.schema-generation.database.action"),
            normalize("hibernate.hbm2ddl.auto"),
            normalize("jakarta.persistence.schema-generation.database.action"),
            normalize("javax.persistence.schema-generation.database.action"));
    private static final Set<String> ALLOWED_VALUES = Set.of("validate", "none");

    private SchemaConfigScanner() {
    }

    public static List<String> scan(Resource file) throws IOException {
        return scan(String.valueOf(file.getFilename()), file, file.getDescription());
    }

    /** In-memory variant for fixtures; the file name decides the format (.properties vs .yml/.yaml). */
    public static List<String> scan(String fileName, String content) throws IOException {
        Resource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
        return scan(fileName, resource, fileName);
    }

    private static List<String> scan(String fileName, Resource resource, String description) throws IOException {
        PropertySourceLoader loader = fileName.endsWith(".properties")
                ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader();
        List<String> violations = new ArrayList<>();
        List<PropertySource<?>> documents = loader.load(fileName, resource);
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i) instanceof EnumerablePropertySource<?> document) {
                for (String key : document.getPropertyNames()) {
                    Object value = document.getProperty(key);
                    if (FORBIDDEN_KEYS.contains(normalize(key)) && !isAllowed(value)) {
                        violations.add(description + " (document " + (i + 1) + ") sets " + key + "=" + value);
                    }
                }
            }
        }
        return violations;
    }

    private static boolean isAllowed(Object value) {
        return value != null && ALLOWED_VALUES.contains(value.toString().trim().toLowerCase(Locale.ROOT));
    }

    private static String normalize(String key) {
        return key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
