package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class SchemaManagementGuardTest {

    private static final String DDL_AUTO = "spring.jpa.hibernate.ddl-auto=";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SchemaManagementGuard.class);

    @ParameterizedTest
    @ValueSource(strings = {"update", "create", "create-drop", "UPDATE", " create "})
    void failsToStartWhenDdlAutoCanChangeTheSchema(String value) {
        runner.withPropertyValues(DDL_AUTO + value).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"validate", "none"})
    void startsWhenSchemaIsOnlyValidatedOrUntouched(String value) {
        runner.withPropertyValues(DDL_AUTO + value).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsClosedWhenDdlAutoIsNotSet() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWhenOverriddenThroughAnEnvironmentVariable() {
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                "test-env", Map.<String, Object>of("SPRING_JPA_HIBERNATE_DDL_AUTO", "update"))))
                .withPropertyValues(DDL_AUTO + "validate")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWhenHibernateNativePropertyForcesSchemaGeneration() {
        runner.withPropertyValues(DDL_AUTO + "validate", "spring.jpa.properties.hibernate.hbm2ddl.auto=create")
                .run(context -> assertThat(context).hasFailed());
    }
}
