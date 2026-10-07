package ar.cetobhb.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Base class for integration tests: full context on a real Postgres container, clean tables after each test. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractPostgresIT {

    @Autowired
    private JdbcTemplate cleanerJdbc;

    @AfterEach
    void cleanDatabase() {
        DatabaseCleaner.clean(cleanerJdbc);
    }
}
