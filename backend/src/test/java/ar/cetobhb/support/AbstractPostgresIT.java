package ar.cetobhb.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests: full context on a real Postgres container. Contract: tables are emptied
 * before AND after every test, so a test never depends on leftovers from a previous test class (including
 * rows left by a failed test or by non-AbstractPostgresIT code sharing the container) and leaves none behind.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractPostgresIT {

    @Autowired
    private JdbcTemplate cleanerJdbc;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        DatabaseCleaner.clean(cleanerJdbc);
    }
}
