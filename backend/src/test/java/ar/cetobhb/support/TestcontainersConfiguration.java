package ar.cetobhb.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres 16 container shared by every integration test (D-20261007-07). It lives in a
 * {@code static final} field, so all Spring test contexts in the JVM (different profiles,
 * {@code @MockitoBean} variants, ...) reuse the same container instead of starting one per context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
