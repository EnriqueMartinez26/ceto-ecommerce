package ar.cetobhb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/** Static half of the ddl-auto guard: no config file in any profile may let Hibernate change the schema. */
class DdlAutoConfigFilesTest {

    private static final List<String> SCHEMA_KEYS =
            List.of("spring.jpa.hibernate.ddl-auto", "spring.jpa.properties.hibernate.hbm2ddl.auto");
    private static final Set<String> ALLOWED = Set.of("validate", "none");
    private static final List<String> PATTERNS = List.of(
            "classpath*:application*.yml", "classpath*:application*.yaml", "classpath*:application*.properties",
            "classpath*:config/application*.yml", "classpath*:config/application*.yaml",
            "classpath*:config/application*.properties");

    @Test
    void noConfigFileOnTheClasspathEnablesSchemaGeneration() throws IOException {
        List<Resource> files = configFiles();
        assertThat(files).as("application config files found").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Resource file : files) {
            Properties properties = load(file);
            for (String key : SCHEMA_KEYS) {
                String value = properties.getProperty(key);
                if (value != null && !ALLOWED.contains(value.trim().toLowerCase(Locale.ROOT))) {
                    violations.add(file.getDescription() + " sets " + key + "=" + value);
                }
            }
        }
        assertThat(violations).as("forbidden schema generation settings").isEmpty();
    }

    private static List<Resource> configFiles() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> files = new ArrayList<>();
        for (String pattern : PATTERNS) {
            files.addAll(List.of(resolver.getResources(pattern)));
        }
        return files;
    }

    private static Properties load(Resource file) throws IOException {
        String name = String.valueOf(file.getFilename());
        if (name.endsWith(".properties")) {
            return PropertiesLoaderUtils.loadProperties(file);
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(file);
        Properties properties = yaml.getObject();
        return properties == null ? new Properties() : properties;
    }
}
