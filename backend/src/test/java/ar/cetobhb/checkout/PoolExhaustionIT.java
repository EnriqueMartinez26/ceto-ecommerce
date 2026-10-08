package ar.cetobhb.checkout;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static ar.cetobhb.support.ContractAssertions.assertMatchesGolden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractHttpIT;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * A request that cannot get a pooled connection within {@code connection-timeout} (3 s) is answered with
 * {@code 503 SERVICE_BUSY} + {@code Retry-After}, on writes and reads alike, and the service recovers as soon as
 * connections come back (REQ-HRD resource exhaustion). The pool is shrunk to 2 connections in this class's own
 * context (cached by Spring, 2 connections) and both are held by the test.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=2")
@SuppressWarnings("try") // the held connections are only held, never referenced, inside their try blocks
class PoolExhaustionIT extends AbstractHttpIT {

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(3);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    private long productId;

    @BeforeEach
    void seed() {
        productId = catalog.createBase(product("Solid shampoo", "45000.00")).id();
        inventory.createStock(productId, 10);
    }

    /** Takes every connection of the pool and gives them back on close. */
    private final class ExhaustedPool implements AutoCloseable {
        private final List<Connection> held = new ArrayList<>();

        ExhaustedPool() throws SQLException {
            int size = ((HikariDataSource) dataSource).getMaximumPoolSize();
            for (int i = 0; i < size; i++) {
                held.add(dataSource.getConnection());
            }
        }

        @Override
        public void close() throws SQLException {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    private MockHttpServletResponse checkout() throws Exception {
        String body = """
                {"buyer":{"name":"Maria Perez","phone":"%s","address":"Calle Falsa 123","zone":"RESTO_DEL_PAIS"},
                 "lines":[{"productId":%d,"quantity":2}]}
                """.formatted(freshBuyer().phone(), productId);
        return send(post("/api/checkout/orders").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertWaitedAboutTheConnectionTimeout(long startedNanos) {
        Duration waited = Duration.ofNanos(System.nanoTime() - startedNanos);
        assertThat(waited).isGreaterThanOrEqualTo(CONNECTION_TIMEOUT.minusMillis(500))
                .isLessThan(CONNECTION_TIMEOUT.plusSeconds(3));
    }

    @Test
    void thePoolIsBoundedAndWaitsAtMostThreeSecondsForAConnection() {
        HikariDataSource pool = (HikariDataSource) dataSource;

        assertThat(pool.getConnectionTimeout()).isEqualTo(CONNECTION_TIMEOUT.toMillis());
        assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
    }

    @Test
    void aCheckoutWithoutAFreeConnectionIs503WithinTheConnectionTimeoutAndNothingIsPersisted() throws Exception {
        try (ExhaustedPool pool = new ExhaustedPool()) {
            long started = System.nanoTime();

            MockHttpServletResponse response = checkout();

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("5");
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertMatchesGolden(response, "error-service-busy.json");
            assertWaitedAboutTheConnectionTimeout(started);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        assertThat(checkout().getStatus()).as("recovers once connections are back").isEqualTo(201);
    }

    @Test
    void aStorefrontReadWithoutAFreeConnectionIs503TooAndRecovers() throws Exception {
        try (ExhaustedPool pool = new ExhaustedPool()) {
            long started = System.nanoTime();

            MockHttpServletResponse response = send(get("/api/store/products").accept(MediaType.APPLICATION_JSON));

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("5");
            assertWaitedAboutTheConnectionTimeout(started);
        }

        assertThat(send(get("/api/store/products").accept(MediaType.APPLICATION_JSON)).getStatus())
                .isEqualTo(200);
    }
}
