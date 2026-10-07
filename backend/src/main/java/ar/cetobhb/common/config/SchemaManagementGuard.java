package ar.cetobhb.common.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fail-closed guard, registered in {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.EnvironmentPostProcessor} (Boot 4 key). It runs while the
 * environment is prepared, before any bean exists, so Hibernate can never execute DDL first.
 * Ordered last so config files, environment variables and command-line arguments are all loaded.
 */
public class SchemaManagementGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SchemaManagementCheck.verify(environment);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
