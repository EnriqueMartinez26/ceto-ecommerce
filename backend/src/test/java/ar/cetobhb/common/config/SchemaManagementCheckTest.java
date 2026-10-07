package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class SchemaManagementCheckTest {

    private static final String DDL_AUTO = "spring.jpa.hibernate.ddl-auto";
    private static final String JAKARTA = "spring.jpa.properties.jakarta.persistence.schema-generation.database.action";
    private static final String JAVAX = "spring.jpa.properties.javax.persistence.schema-generation.database.action";
    private static final String HBM2DDL = "spring.jpa.properties.hibernate.hbm2ddl.auto";
    private static final String NATIVE_JAKARTA = "jakarta.persistence.schema-generation.database.action";
    private static final String NATIVE_HBM2DDL = "hibernate.hbm2ddl.auto";

    private static StandardEnvironment env(String key, String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(key, value)));
        return environment;
    }

    private static StandardEnvironment withValidDdlAuto(String key, String value) {
        StandardEnvironment environment = env(key, value);
        environment.getPropertySources().addFirst(new MapPropertySource("base", Map.of(DDL_AUTO, "validate")));
        return environment;
    }

    private static StandardEnvironment envVar(String name, String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-env", Map.<String, Object>of(name, value)));
        return environment;
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "create", "create-drop", "UPDATE", " create "})
    void rejectsDdlAutoThatCanChangeTheSchema(String value) {
        assertThatThrownBy(() -> SchemaManagementCheck.verify(env(DDL_AUTO, value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining(DDL_AUTO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"validate", "none", " VALIDATE ", "None"})
    void acceptsValidateOrNone(String value) {
        assertThatCode(() -> SchemaManagementCheck.verify(env(DDL_AUTO, value))).doesNotThrowAnyException();
    }

    @Test
    void failsClosedWhenDdlAutoIsNotSet() {
        assertThatThrownBy(() -> SchemaManagementCheck.verify(new StandardEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DDL_AUTO);
    }

    @Test
    void rejectsDdlAutoOverriddenThroughEnvironmentVariable() {
        StandardEnvironment environment = envVar("SPRING_JPA_HIBERNATE_DDL_AUTO", "update");
        environment.getPropertySources().addLast(new MapPropertySource("base", Map.of(DDL_AUTO, "validate")));
        assertThatThrownBy(() -> SchemaManagementCheck.verify(environment))
                .hasMessageContaining("Refusing to start");
    }

    @ParameterizedTest
    @ValueSource(strings = {JAKARTA, JAVAX, HBM2DDL, NATIVE_JAKARTA, NATIVE_HBM2DDL})
    void rejectsEachSchemaGenerationChannel(String key) {
        assertThatThrownBy(() -> SchemaManagementCheck.verify(withValidDdlAuto(key, "create")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "drop-and-create", "CREATE", "create-drop", "bogus"})
    void rejectsAnyNonAllowedValueOnAChannel(String value) {
        assertThatThrownBy(() -> SchemaManagementCheck.verify(withValidDdlAuto(JAKARTA, value)))
                .hasMessageContaining(JAKARTA);
    }

    @ParameterizedTest
    @ValueSource(strings = {JAKARTA, JAVAX, HBM2DDL, NATIVE_JAKARTA, NATIVE_HBM2DDL})
    void channelsAcceptNoneAndValidateCaseInsensitively(String key) {
        assertThatCode(() -> SchemaManagementCheck.verify(withValidDdlAuto(key, " None "))).doesNotThrowAnyException();
        assertThatCode(() -> SchemaManagementCheck.verify(withValidDdlAuto(key, "VALIDATE"))).doesNotThrowAnyException();
    }

    @Test
    void channelsAreOptional() {
        assertThatCode(() -> SchemaManagementCheck.verify(withValidDdlAuto("unrelated.key", "x")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SPRING_JPA_PROPERTIES_JAKARTA_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION",
            "SPRING_JPA_PROPERTIES_JAVAX_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION",
            "SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO"})
    void rejectsEachChannelPassedAsRelaxedEnvironmentVariable(String name) {
        StandardEnvironment environment = envVar(name, "create");
        environment.getPropertySources().addLast(new MapPropertySource("base", Map.of(DDL_AUTO, "validate")));
        assertThatThrownBy(() -> SchemaManagementCheck.verify(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start");
    }
}
