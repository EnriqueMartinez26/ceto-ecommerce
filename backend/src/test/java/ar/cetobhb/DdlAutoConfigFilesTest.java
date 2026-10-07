package ar.cetobhb;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.SchemaConfigScanner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Static half of the schema guard: no config file in any profile may let Hibernate change the schema. */
class DdlAutoConfigFilesTest {

    private static final List<String> PATTERNS = List.of(
            "classpath*:application*.yml", "classpath*:application*.yaml", "classpath*:application*.properties",
            "classpath*:config/application*.yml", "classpath*:config/application*.yaml",
            "classpath*:config/application*.properties");

    @Test
    void noConfigFileOnTheClasspathEnablesSchemaGeneration() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> files = new ArrayList<>();
        for (String pattern : PATTERNS) {
            files.addAll(List.of(resolver.getResources(pattern)));
        }
        assertThat(files).as("application config files found").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Resource file : files) {
            violations.addAll(SchemaConfigScanner.scan(file));
        }
        assertThat(violations).as("forbidden schema generation settings").isEmpty();
    }

    @Test
    void detectsViolationInAnEarlierDocumentOfAMultiDocumentYaml() throws IOException {
        String yaml = """
                spring:
                  jpa:
                    hibernate:
                      ddl-auto: create
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                  jpa:
                    hibernate:
                      ddl-auto: validate
                """;
        assertThat(SchemaConfigScanner.scan("application.yml", yaml))
                .singleElement().asString().contains("ddl-auto").contains("create");
    }

    @Test
    void detectsViolationInALaterDocumentOfAMultiDocumentYaml() throws IOException {
        String yaml = """
                spring.jpa.hibernate.ddl-auto: validate
                ---
                spring.jpa.hibernate.ddl-auto: update
                """;
        assertThat(SchemaConfigScanner.scan("application.yaml", yaml)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.jpa.hibernate.ddl_auto", "spring.jpa.hibernate.ddlAuto",
            "spring.jpa.hibernate.ddl-auto", "SPRING_JPA_HIBERNATE_DDL_AUTO"})
    void detectsRelaxedDdlAutoKeysInProperties(String key) throws IOException {
        assertThat(SchemaConfigScanner.scan("application-dev.properties", key + "=create-drop")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
            "spring.jpa.properties.javax.persistence.schema-generation.database.action",
            "spring.jpa.properties.hibernate.hbm2ddl.auto",
            "jakarta.persistence.schema-generation.database.action",
            "hibernate.hbm2ddl.auto"})
    void detectsSchemaGenerationChannelsInProperties(String key) throws IOException {
        assertThat(SchemaConfigScanner.scan("application.properties", key + "=drop-and-create")).hasSize(1);
    }

    @Test
    void detectsSchemaGenerationChannelNestedInYaml() throws IOException {
        String yaml = """
                spring:
                  jpa:
                    properties:
                      jakarta.persistence.schema-generation.database.action: create
                """;
        assertThat(SchemaConfigScanner.scan("application.yml", yaml)).hasSize(1);
    }

    @Test
    void allowsValidateNoneAndMissingKeysInEveryDocument() throws IOException {
        String yaml = """
                spring.jpa.hibernate.ddl-auto: " Validate "
                ---
                spring.jpa.hibernate.ddl-auto: none
                ---
                server.port: 8080
                """;
        assertThat(SchemaConfigScanner.scan("application.yml", yaml)).isEmpty();
    }
}
