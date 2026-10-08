package ar.cetobhb.checkout;

import static ar.cetobhb.support.BuyerFixtures.freshBuyer;
import static ar.cetobhb.support.CatalogFixtures.product;
import static ar.cetobhb.support.ContractAssertions.assertMatchesGolden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.config.OrderProperties;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.service.OrderExpiryJob;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractHttpIT;
import ar.cetobhb.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Postgres {@code lock_timeout} bounds every lock wait of the application (carry-forward S5c, REQ-HRD resource
 * exhaustion): a request parked behind a row lock gives up after 5 s with {@code 503 SERVICE_BUSY} and
 * {@code Retry-After}, leaves nothing behind and never turns into a 500 (verification U-7, U-10, U-12). The per-phone
 * advisory lock does not wait at all (D-20261008-01): its holder gets an immediate 503 with {@code Retry-After: 1}. The lock
 * holder is a plain pooled connection; a watchdog releases it after 20 s so a missing timeout shows up as a failed
 * assertion and not as a hung build.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@SuppressWarnings("try") // the lock holders are only held, never referenced, inside their try blocks
class LockTimeoutIT extends AbstractHttpIT {

    private static final String PATH = "/api/checkout/orders";
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private OrderLifecycleService lifecycle;

    @Autowired
    private MutableClock clock;

    private long productId;

    @BeforeEach
    void seedStock() {
        productId = catalog.createBase(product("Solid shampoo", "45000.00")).id();
        inventory.createStock(productId, 10);
    }

    /** Holds a lock in an open transaction until {@link #close} (or the watchdog, as a safety net). */
    private final class LockHolder implements AutoCloseable {
        private final Connection connection;
        private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();

        LockHolder(String sql, Object parameter) throws SQLException {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, parameter);
                statement.execute();
            }
            watchdog.schedule(this::close, 20, TimeUnit.SECONDS);
        }

        @Override
        public synchronized void close() {
            try {
                if (!connection.isClosed()) {
                    connection.rollback();
                    connection.close();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not release the test lock", e);
            } finally {
                watchdog.shutdownNow();
            }
        }
    }

    private LockHolder holdStockRow() throws SQLException {
        return new LockHolder("SELECT 1 FROM inventory_stock WHERE product_id = ? FOR UPDATE", productId);
    }

    private LockHolder holdPhone(String phone) throws SQLException {
        return new LockHolder("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", phone);
    }

    private String body(String phone) {
        return """
                {"buyer":{"name":"Maria Perez","phone":"%s","address":"Calle Falsa 123","zone":"RESTO_DEL_PAIS"},
                 "lines":[{"productId":%d,"quantity":2}]}
                """.formatted(phone, productId);
    }

    private MockHttpServletResponse checkout(String phone) throws Exception {
        return send(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body(phone).getBytes(StandardCharsets.UTF_8)));
    }

    private long orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    private long reserved() {
        return jdbc.queryForObject("SELECT coalesce(sum(reserved), 0) FROM inventory_stock", Long.class);
    }

    /**
     * The wait is the configured timeout: not instant, not unbounded. The upper bound is loose on purpose (a slow CI
     * machine must not fail the build); what it rules out is an unbounded wait.
     */
    private static void assertWaitedAboutTheLockTimeout(long startedNanos) {
        Duration waited = Duration.ofNanos(System.nanoTime() - startedNanos);
        assertThat(waited).isGreaterThanOrEqualTo(LOCK_TIMEOUT.minusMillis(500))
                .isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void everyApplicationConnectionRunsWithAFiveSecondLockTimeout() {
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    }

    @Test
    void everyApplicationConnectionRunsWithAnIdleInTransactionBackstop() {
        // D-20261008-01: a transaction that holds locks but stopped talking is killed after 30 s.
        assertThat(jdbc.queryForObject("SHOW idle_in_transaction_session_timeout", String.class)).isEqualTo("30s");
    }

    @Test
    void aPlacementParkedBehindAStockRowLockIsAnsweredWith503AfterTheLockTimeout() throws Exception {
        String phone = freshBuyer().phone();
        try (LockHolder holder = holdStockRow()) {
            long started = System.nanoTime();

            MockHttpServletResponse response = checkout(phone);

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("5");
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertMatchesGolden(response, "error-service-busy.json");
            assertWaitedAboutTheLockTimeout(started);
        }
        assertThat(orderCount()).isZero();
        assertThat(reserved()).isZero();
        assertThat(checkout(phone).getStatus()).as("recovers once the lock is gone").isEqualTo(201);
    }

    @Test
    void aPlacementOfAPhoneWhoseAdvisoryLockIsHeldIsAnsweredAtOnceWith503AndRetryAfterOne() throws Exception {
        String phone = freshBuyer().phone();
        try (LockHolder holder = holdPhone(phone)) {
            long started = System.nanoTime();

            MockHttpServletResponse response = checkout(phone);

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertMatchesGolden(response, "error-service-busy.json");
            // It did not wait for the lock (D-20261008-01): far below the 5 s lock_timeout, no pooled connection pinned.
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LOCK_TIMEOUT.minusSeconds(1));

            long otherStarted = System.nanoTime();
            assertThat(checkout(freshBuyer().phone()).getStatus()).as("another phone is not blocked").isEqualTo(201);
            assertThat(Duration.ofNanos(System.nanoTime() - otherStarted)).isLessThan(Duration.ofSeconds(10));
        }
        assertThat(orderCount()).isEqualTo(1L);
        assertThat(reserved()).isEqualTo(2L);
        assertThat(checkout(phone).getStatus()).as("the same phone recovers once the lock is gone").isEqualTo(201);
    }

    /**
     * The expiry job is a scheduled task: a lock timeout in its batch is a RuntimeException that falls back to one
     * order at a time, and a timeout there is logged and counted as failed. The tick ends normally, the order stays
     * pending (its reservation is untouched) and the next tick expires it.
     */
    @Test
    void anExpiryTickBehindAStockRowLockEndsWithoutThrowingAndRetriesNextTick() throws Exception {
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), freshBuyer(),
                List.of(new PlaceOrderCommand.Line(productId, 1))));
        clock.set(MutableClock.START.plus(Duration.ofHours(49)));
        OrderExpiryJob job = new OrderExpiryJob(lifecycle,
                new OrderProperties(48, 10, 20, 3, new OrderProperties.Expiry(10, 60_000, 10)));

        try (LockHolder holder = holdStockRow()) {
            job.run();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'PAGO_PENDIENTE'", Long.class))
                .isEqualTo(1L);
        assertThat(reserved()).isEqualTo(1L);

        job.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'PAGO_PENDIENTE'", Long.class))
                .isZero();
        assertThat(reserved()).isZero();
    }
}
