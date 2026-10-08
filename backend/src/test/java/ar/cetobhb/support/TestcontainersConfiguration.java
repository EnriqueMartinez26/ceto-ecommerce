package ar.cetobhb.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres 16 container shared by every integration test (D-20261007-07). It lives in a
 * {@code static final} field, so all Spring test contexts in the JVM (different profiles,
 * {@code @MockitoBean} variants, ...) reuse the same container instead of starting one per context.
 *
 * <p>It also gives every context that imports it the administrator credential of the JVM
 * ({@link AdminTestCredentials}: a random password and its run-time hash, never committed) and counts the BCrypt work
 * of the admin account ({@link CountingPasswordEncoder}). Both ride on this class so the two existing context shapes
 * (MockMvc and random port) stay two contexts, not four.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    DynamicPropertyRegistrar adminCredentialsRegistrar() {
        return registry -> {
            registry.add("ceto.admin.username", AdminTestCredentials::username);
            registry.add("ceto.admin.password-hash", AdminTestCredentials::passwordHash);
        };
    }

    /** Wraps the BCrypt encoder of the admin account (bean "adminBcryptEncoder") in a counting decorator. */
    @Bean
    static BeanPostProcessor countAdminBcryptWork() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if ("adminBcryptEncoder".equals(beanName) && bean instanceof PasswordEncoder encoder) {
                    return new CountingPasswordEncoder(encoder);
                }
                return bean;
            }
        };
    }
}
