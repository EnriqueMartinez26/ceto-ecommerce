package ar.cetobhb.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests: full context on a real Postgres container. Contract: tables are emptied
 * before AND after every test, so a test never depends on leftovers from a previous test class (including
 * rows left by a failed test or by non-AbstractPostgresIT code sharing the container) and leaves none behind.
 * The clock is a {@link MutableClock} reset to {@link MutableClock#START} before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
public abstract class AbstractPostgresIT {

    @Autowired
    private JdbcTemplate cleanerJdbc;

    @Autowired
    private MutableClock testClock;

    @BeforeEach
    void resetClock() {
        testClock.reset();
    }

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        DatabaseCleaner.clean(cleanerJdbc);
    }
}
