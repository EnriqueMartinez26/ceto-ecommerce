package ar.cetobhb;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/**
 * Proves the guard is wired into the real application: it must abort startup from the environment
 * phase, before any bean (and therefore any DDL) exists. Fails if the {@code META-INF/spring.factories}
 * registration is removed. No database is needed because the failure happens before the context is built.
 */
class SchemaGuardWiringIT {

    private static final String REFUSAL = "Refusing to start";

    @Test
    void realApplicationFailsToStartWithDdlAutoUpdate() {
        assertThatThrownBy(() -> run("--spring.jpa.hibernate.ddl-auto=update"))
                .hasStackTraceContaining(REFUSAL)
                .hasStackTraceContaining("spring.jpa.hibernate.ddl-auto");
    }

    @Test
    void realApplicationFailsToStartWithJakartaSchemaGeneration() {
        assertThatThrownBy(() -> run("--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.properties.jakarta.persistence.schema-generation.database.action=create"))
                .hasStackTraceContaining(REFUSAL)
                .hasStackTraceContaining("jakarta.persistence.schema-generation.database.action");
    }

    @Test
    void realApplicationFailsToStartWithBracketedHbm2ddlArgument() {
        assertThatThrownBy(() -> run("--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.properties[hibernate.hbm2ddl.auto]=create"))
                .hasStackTraceContaining(REFUSAL)
                .hasStackTraceContaining("hibernate.hbm2ddl.auto");
    }

    private static void run(String... args) {
        SpringApplication application = new SpringApplication(CetoBackendApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args).close();
    }
}
